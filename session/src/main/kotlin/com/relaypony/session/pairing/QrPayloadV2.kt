package com.relaypony.session.pairing

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Pairing QR v2 (PROTOCOL_v3.md section 3):
 *
 *   RP2|<scheme 2-hex>|<handle>|<form(name)>|<b64u(pairNonce)>|<inboxId>|<form(relay)>
 *
 * Adds a fresh single-use pairing nonce (so a screenshot of an old QR can't complete a pairing),
 * this device's private relay inbox, and its relay when it isn't the default. [QrPayload] stays as
 * the v1 codec for "Show legacy QR" and for scanning 3.x devices.
 */
data class QrPayloadV2(
    val schemeId: Byte,
    val recipientHandle: String,
    val deviceName: String,
    val pairNonce: ByteArray,
    val inboxId: String,
    /** Normalized relay base URL, empty for the default relay. */
    val relay: String = "",
) {
    fun encode(): String {
        require(recipientHandle.startsWith("age1")) { "bad handle" }
        require(pairNonce.size == NONCE_BYTES) { "pair nonce must be $NONCE_BYTES bytes" }
        require(InboxIds.isValid(inboxId)) { "bad inbox id" }
        return listOf(
            MAGIC,
            "%02x".format(schemeId.toInt() and 0xff),
            recipientHandle,
            URLEncoder.encode(deviceName, "UTF-8"),
            B64u.encode(pairNonce),
            inboxId,
            URLEncoder.encode(relay, "UTF-8"),
        ).joinToString("|")
    }

    override fun equals(other: Any?): Boolean = other is QrPayloadV2 &&
        schemeId == other.schemeId && recipientHandle == other.recipientHandle &&
        deviceName == other.deviceName && pairNonce.contentEquals(other.pairNonce) &&
        inboxId == other.inboxId && relay == other.relay

    override fun hashCode(): Int = recipientHandle.hashCode() * 31 + pairNonce.contentHashCode()

    companion object {
        const val MAGIC = "RP2"
        const val NONCE_BYTES = 16

        fun newNonce(rng: SecureRandom = SecureRandom()): ByteArray = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }

        fun decode(text: String): QrPayloadV2 {
            val p = text.split("|")
            require(p.size == 7) { "malformed pairing payload (expected 7 fields)" }
            require(p[0] == MAGIC) { "not a v2 pairing payload" }
            val scheme = (p[1].toIntOrNull(16) ?: throw IllegalArgumentException("bad scheme field")).toByte()
            require(p[2].startsWith("age1") && p[2].length in 20..200) { "bad handle" }
            val nonce = B64u.decode(p[4])
            require(nonce.size == NONCE_BYTES) { "bad pair nonce" }
            require(InboxIds.isValid(p[5])) { "bad inbox id" }
            return QrPayloadV2(scheme, p[2], URLDecoder.decode(p[3], "UTF-8"), nonce, p[5], URLDecoder.decode(p[6], "UTF-8"))
        }
    }
}

/** A scanned pairing QR of either version. */
sealed class ScannedQr {
    data class V1(val payload: QrPayload) : ScannedQr()
    data class V2(val payload: QrPayloadV2) : ScannedQr()

    companion object {
        /** Decodes `RP2` or `RP1`; throws IllegalArgumentException for anything else. */
        fun decode(text: String): ScannedQr {
            val t = text.trim()
            return if (t.startsWith("${QrPayloadV2.MAGIC}|")) V2(QrPayloadV2.decode(t)) else V1(QrPayload.decode(t))
        }
    }
}

/** Private relay inbox ids: 32 random bytes, base64url without padding (43 characters). */
object InboxIds {
    const val BYTES = 32
    private val PATTERN = Regex("^[A-Za-z0-9_-]{43}$")

    fun generate(rng: SecureRandom = SecureRandom()): String = B64u.encode(ByteArray(BYTES).also { rng.nextBytes(it) })

    fun isValid(id: String): Boolean = PATTERN.matches(id) && runCatching { B64u.decode(id).size == BYTES }.getOrDefault(false)
}
