package com.relaypony.transport

import java.io.ByteArrayOutputStream

/**
 * The RelayPony discovery beacon: a small UDP broadcast protocol that finds peers where mDNS can't.
 *
 * Why it exists. mDNS is multicast, and multicast is the first thing a network drops. It does not
 * cross a phone's hotspot (Android's mDNS follows the default network, which is mobile data while
 * tethering), it is filtered on plenty of guest and hotel APs, and it is disabled outright on some
 * enterprise Wi-Fi. Broadcast survives in most of those places, and — critically — a UDP socket can
 * be bound to a specific local interface address, which is what lets a tethering phone speak on the
 * subnet it is actually sharing rather than the one the OS considers "default".
 *
 * This is a side channel, not a wire change: [WireProtocol]'s frozen v1 session format is untouched.
 * The beacon only answers "who is out there and on what port", the same question mDNS answers, and
 * carries the same three facts the TXT record does.
 *
 * Privacy: an ANNOUNCE puts the device name and the public age handle on the local broadcast domain
 * — exactly what the existing mDNS advertisement already publishes, no more. Trust is still pinned
 * by handle, so a forged announcement gets an attacker nothing: files are encrypted to the pinned
 * key, and a device that isn't pinned cannot be sent to at all.
 *
 * Frame: ["RPB1"][type u8][body]
 *   PROBE    (0x01) body: empty. "Anyone there?" — broadcast.
 *   ANNOUNCE (0x02) body: [tcpPort u16][maxWire u8][nameLen u16][name][handleLen u16][handle]
 *                   Sent unicast in reply to a PROBE, and broadcast periodically for late joiners.
 */
object Beacon {

    /**
     * The default TCP port a receiver listens on. Shared here rather than defined per platform,
     * because the whole point of a stable port is that both ends agree on it: it makes a firewall
     * rule writable, lets a subnet scan mean something, and gives a user an address worth noting
     * down. Before this existed both apps took whatever ephemeral port the OS handed out, so an
     * address was only ever valid for one run.
     */
    const val DEFAULT_TRANSFER_PORT = 45789

    /** UDP port for the beacon itself: one above the transfer port, so the pair is easy to recall. */
    const val PORT = DEFAULT_TRANSFER_PORT + 1

    /** Bump if the frame layout ever changes; unknown magic is ignored rather than mis-parsed. */
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'P'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())

    private const val TYPE_PROBE = 0x01
    private const val TYPE_ANNOUNCE = 0x02

    /** Anything larger is not ours. Keeps a hostile packet from making us allocate. */
    const val MAX_FRAME = 512

    /** Names are cosmetic and attacker-controlled; cap them so a frame always fits in one datagram. */
    private const val MAX_NAME_BYTES = 64
    private const val MAX_HANDLE_BYTES = 256

    sealed interface Message {
        data object Probe : Message
        data class Announce(
            val tcpPort: Int,
            val maxWire: Int,
            val deviceName: String,
            val recipientHandle: String,
        ) : Message
    }

    fun encodeProbe(): ByteArray = MAGIC + byteArrayOf(TYPE_PROBE.toByte())

    fun encodeAnnounce(tcpPort: Int, maxWire: Int, deviceName: String, recipientHandle: String): ByteArray {
        require(tcpPort in 1..0xFFFF) { "tcpPort out of range: $tcpPort" }
        require(recipientHandle.isNotEmpty()) { "cannot announce an empty handle" }
        val name = truncateUtf8(deviceName, MAX_NAME_BYTES)
        val handle = recipientHandle.toByteArray(Charsets.UTF_8)
        require(handle.size <= MAX_HANDLE_BYTES) { "handle too long: ${handle.size}" }

        val out = ByteArrayOutputStream(MAX_FRAME)
        out.write(MAGIC)
        out.write(TYPE_ANNOUNCE)
        writeU16(out, tcpPort)
        out.write(maxWire.coerceIn(1, 0xFF))
        writeU16(out, name.size)
        out.write(name)
        writeU16(out, handle.size)
        out.write(handle)
        return out.toByteArray()
    }

    /**
     * Parse a received datagram. Returns null for anything that isn't a well-formed beacon frame —
     * this socket is on a broadcast port and will see other applications' traffic, so silently
     * ignoring nonsense is the correct behaviour, not an error.
     */
    fun decode(data: ByteArray, length: Int = data.size): Message? {
        if (length < MAGIC.size + 1 || length > MAX_FRAME) return null
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return null
        var p = MAGIC.size
        return when (data[p++].toInt() and 0xFF) {
            TYPE_PROBE -> Message.Probe
            TYPE_ANNOUNCE -> {
                if (length < p + 5) return null
                val tcpPort = readU16(data, p); p += 2
                val maxWire = data[p++].toInt() and 0xFF
                val nameLen = readU16(data, p); p += 2
                if (nameLen > MAX_NAME_BYTES || length < p + nameLen + 2) return null
                val name = String(data, p, nameLen, Charsets.UTF_8); p += nameLen
                val handleLen = readU16(data, p); p += 2
                if (handleLen == 0 || handleLen > MAX_HANDLE_BYTES || length < p + handleLen) return null
                val handle = String(data, p, handleLen, Charsets.UTF_8)
                if (tcpPort == 0) return null
                Message.Announce(tcpPort, maxOf(1, maxWire), name, handle)
            }
            else -> null
        }
    }

    /** Cut a string to fit [max] bytes without splitting a UTF-8 sequence or a surrogate pair. */
    private fun truncateUtf8(s: String, max: Int): ByteArray {
        val full = s.toByteArray(Charsets.UTF_8)
        if (full.size <= max) return full
        var end = max
        while (end > 0 && (full[end].toInt() and 0xC0) == 0x80) end--   // back off continuation bytes
        return full.copyOf(end)
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}
