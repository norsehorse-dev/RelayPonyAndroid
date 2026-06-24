package com.relaypony.session.pairing

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The content encoded in a pairing QR code. Carries the peer's identity out-of-band so it can be
 * pinned with no chance for a network party to tamper with it (the whole point of QR pairing vs.
 * trusting the mDNS TXT record).
 *
 * Wire form (a single QR-friendly line, no characters that collide with the '|' delimiter):
 *   RP<version>|<schemeId 2-hex>|<recipientHandle>|<url-encoded deviceName>
 * e.g. RP1|01|age1qqq...|Kevins%20Phone
 *
 * Connection info (host/port) is intentionally NOT here in Phase 4: peers are found over mDNS, so
 * the QR's job is purely identity/key pinning. A QR-only direct-connect variant can add it later.
 */
data class QrPayload(
    val version: Int,
    val schemeId: Byte,
    val recipientHandle: String,
    val deviceName: String,
) {
    fun encode(): String {
        require(version in 0..99) { "version out of range" }
        require(recipientHandle.isNotEmpty()) { "empty recipient handle" }
        val name = URLEncoder.encode(deviceName, "UTF-8")
        return "RP$version|%02x|%s|%s".format(schemeId.toInt() and 0xff, recipientHandle, name)
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun decode(text: String): QrPayload {
            val parts = text.split("|")
            require(parts.size == 4) { "malformed pairing payload (expected 4 fields)" }
            val magicVer = parts[0]
            require(magicVer.startsWith("RP")) { "not a RelayPony pairing payload" }
            val version = magicVer.removePrefix("RP").toIntOrNull()
                ?: throw IllegalArgumentException("bad version field")
            val scheme = (parts[1].toIntOrNull(16)
                ?: throw IllegalArgumentException("bad scheme field")).toByte()
            val handle = parts[2]
            require(handle.isNotEmpty()) { "empty recipient handle" }
            val name = URLDecoder.decode(parts[3], "UTF-8")
            return QrPayload(version, scheme, handle, name)
        }
    }
}
