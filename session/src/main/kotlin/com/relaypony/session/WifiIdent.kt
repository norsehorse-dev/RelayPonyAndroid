package com.relaypony.session

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Identity each side announces over a freshly formed Wi-Fi Direct link, before any transfer.
 * Wi-Fi Direct provides no mDNS TXT record to carry a device's key, so the two devices exchange
 * these to learn each other's handle (which the sender verifies against QR-pinned trust) and to
 * agree which side sends.
 */
data class Ident(
    val schemeId: Int,
    val handle: String,
    val deviceName: String,
    val wantsToSend: Boolean,
)

/**
 * Binary codec and role resolution for the Wi-Fi Direct identity handshake. This is a distinct,
 * pre-session handshake: the frozen session wire format (WIRE_VERSION 1) is untouched; this
 * handshake carries its own [VERSION], bumped independently if it ever changes.
 *
 * Frame: [version(1)][schemeId(1)][wantsToSend(1)][nameLen(2 BE)][name][handleLen(2 BE)][handle]
 */
object WifiIdent {
    const val VERSION = 1

    fun writeTo(out: OutputStream, ident: Ident) {
        val name = ident.deviceName.toByteArray(Charsets.UTF_8)
        val handle = ident.handle.toByteArray(Charsets.UTF_8)
        out.write(VERSION)
        out.write(ident.schemeId and 0xFF)
        out.write(if (ident.wantsToSend) 1 else 0)
        writeU16(out, name.size)
        out.write(name)
        writeU16(out, handle.size)
        out.write(handle)
        out.flush()
    }

    fun readFrom(inp: InputStream): Ident {
        val version = readByte(inp)
        require(version == VERSION) { "unsupported handshake version $version" }
        val schemeId = readByte(inp)
        val wantsToSend = readByte(inp) != 0
        val name = readBlock(inp, readU16(inp))
        val handle = readBlock(inp, readU16(inp))
        return Ident(schemeId, String(handle, Charsets.UTF_8), String(name, Charsets.UTF_8), wantsToSend)
    }

    /** True if [mine] is the sender. Throws if both or neither side wants to send. */
    fun resolveISend(mine: Ident, theirs: Ident): Boolean {
        if (mine.wantsToSend && !theirs.wantsToSend) return true
        if (!mine.wantsToSend && theirs.wantsToSend) return false
        throw IllegalStateException(
            if (mine.wantsToSend) "both devices are set to send" else "neither device is set to send",
        )
    }

    private fun writeU16(out: OutputStream, v: Int) {
        require(v in 0..0xFFFF) { "length out of range: $v" }
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun readByte(inp: InputStream): Int {
        val b = inp.read()
        if (b < 0) throw EOFException("unexpected end of handshake")
        return b
    }

    private fun readU16(inp: InputStream): Int {
        val hi = readByte(inp)
        val lo = readByte(inp)
        return (hi shl 8) or lo
    }

    private fun readBlock(inp: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(buf, off, len - off)
            if (n < 0) throw EOFException("unexpected end of handshake block")
            off += n
        }
        return buf
    }
}
