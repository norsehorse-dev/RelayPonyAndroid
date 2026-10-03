package com.relaypony.session.pairing

import com.relaypony.crypto.CryptoProvider
import com.relaypony.crypto.Identity
import com.relaypony.crypto.PeerKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Messages of mutual QR pairing and inbox sharing (PROTOCOL_v3.md sections 4 and 5.3). Each is a
 * `|`-separated text line, age-sealed to the recipient's handle. PAIR_ACK and the inbox
 * announcement also carry an HMAC under a pair key, which proves the sender holds its age secret.
 */
object PairMessages {
    const val ACK_INFO = "relaypony/pair/ack/v1"
    const val INBOX_INFO = "relaypony/inbox/v1"

    private fun form(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun unform(s: String) = URLDecoder.decode(s, "UTF-8")

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun hmac(key: ByteArray, body: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8))
    }

    private fun splitTagged(plain: String): Pair<String, ByteArray> {
        val cut = plain.lastIndexOf('|')
        require(cut > 0) { "malformed message" }
        val tagHex = plain.substring(cut + 1)
        require(tagHex.length == 64) { "bad tag" }
        return plain.substring(0, cut) to unhex(tagHex)
    }

    // ---- PAIR_REQ (B -> A) ----

    data class PairRequest(
        val handle: String,
        val name: String,
        val inboxId: String,
        /** Normalized relay of the requester, empty for the default. */
        val relay: String,
        val nonceA: ByteArray,
        val nonceB: ByteArray,
    ) {
        fun encode(): String = listOf(
            "RPQ1", handle, form(name), inboxId, form(relay), B64u.encode(nonceA), B64u.encode(nonceB),
        ).joinToString("|")

        override fun equals(other: Any?) = other is PairRequest && encode() == other.encode()
        override fun hashCode() = encode().hashCode()

        companion object {
            fun decode(plain: String): PairRequest {
                val p = plain.split("|")
                require(p.size == 7 && p[0] == "RPQ1") { "not a pair request" }
                require(p[1].startsWith("age1")) { "bad handle" }
                require(InboxIds.isValid(p[3])) { "bad inbox id" }
                val na = B64u.decode(p[5]); val nb = B64u.decode(p[6])
                require(na.size == QrPayloadV2.NONCE_BYTES && nb.size == QrPayloadV2.NONCE_BYTES) { "bad nonce" }
                return PairRequest(p[1], unform(p[2]), p[3], unform(p[4]), na, nb)
            }
        }
    }

    // ---- PAIR_ACK (A -> B) ----

    data class PairAck(val handleA: String, val handleB: String, val nonceB: ByteArray, val ok: Boolean) {
        fun body(): String = listOf("RPA1", handleA, handleB, B64u.encode(nonceB), if (ok) "ok" else "no").joinToString("|")

        override fun equals(other: Any?) = other is PairAck && body() == other.body()
        override fun hashCode() = body().hashCode()
    }

    /** A's side: the tagged plaintext, from A's secret and B's handle. */
    fun encodeAck(ack: PairAck, myScalar: ByteArray): String {
        val key = PeerKey.deriveFromHandles(myScalar, ack.handleA, ack.handleB, ACK_INFO)
        val body = ack.body()
        return body + "|" + hex(hmac(key, body))
    }

    /** B's side: parse and verify. [expectedA] is the handle B scanned; the tag must prove it. */
    fun decodeAck(plain: String, myScalar: ByteArray, myHandle: String, expectedA: String): PairAck {
        val (body, tag) = splitTagged(plain)
        val p = body.split("|")
        require(p.size == 5 && p[0] == "RPA1") { "not a pair ack" }
        require(p[1] == expectedA && p[2] == myHandle) { "ack not for this pairing" }
        require(p[4] == "ok" || p[4] == "no") { "bad result" }
        val key = PeerKey.deriveFromHandles(myScalar, myHandle, expectedA, ACK_INFO)
        require(MessageDigest.isEqual(hmac(key, body), tag)) { "bad ack tag" }
        return PairAck(p[1], p[2], B64u.decode(p[3]), p[4] == "ok")
    }

    // ---- Inbox announcement (either direction, between already-paired devices) ----

    data class InboxAnnouncement(val from: String, val to: String, val inboxId: String, val relay: String) {
        fun body(): String = listOf("RPI1", from, to, inboxId, form(relay)).joinToString("|")
    }

    fun encodeInbox(a: InboxAnnouncement, myScalar: ByteArray): String {
        val key = PeerKey.deriveFromHandles(myScalar, a.from, a.to, INBOX_INFO)
        val body = a.body()
        return body + "|" + hex(hmac(key, body))
    }

    /** Parse and verify. The caller still checks that `from` is pinned. */
    fun decodeInbox(plain: String, myScalar: ByteArray, myHandle: String): InboxAnnouncement {
        val (body, tag) = splitTagged(plain)
        val p = body.split("|")
        require(p.size == 5 && p[0] == "RPI1") { "not an inbox announcement" }
        require(p[2] == myHandle && p[1].startsWith("age1")) { "announcement not for this device" }
        require(InboxIds.isValid(p[3])) { "bad inbox id" }
        val key = PeerKey.deriveFromHandles(myScalar, myHandle, p[1], INBOX_INFO)
        require(MessageDigest.isEqual(hmac(key, body), tag)) { "bad announcement tag" }
        return InboxAnnouncement(p[1], p[2], p[3], unform(p[4]))
    }

    // ---- Sealing and dispatch ----

    /** age-seal a plaintext line to [toHandle]. */
    fun seal(provider: CryptoProvider, toHandle: String, plain: String): ByteArray {
        val recipient = provider.recipientFromQr(toHandle.toByteArray(Charsets.UTF_8))
        val out = ByteArrayOutputStream()
        provider.encryptStream(listOf(recipient), ByteArrayInputStream(plain.toByteArray(Charsets.UTF_8)), out)
        return out.toByteArray()
    }

    /** Largest sealed message accepted before decrypting. */
    const val MAX_SEALED_BYTES = 16 * 1024

    /** Open a sealed message to its plaintext line. Throws on anything that isn't for us. */
    fun open(provider: CryptoProvider, identity: Identity, sealed: ByteArray): String {
        require(sealed.size <= MAX_SEALED_BYTES) { "sealed message too large" }
        val out = ByteArrayOutputStream()
        provider.decryptStream(identity, ByteArrayInputStream(sealed), out)
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** What a sealed relay payload turned out to be, by its plaintext prefix (section 6.1). */
    enum class Kind { SIGNAL, PAIR_REQUEST, PAIR_ACK, INBOX, UNKNOWN }

    fun kindOf(plain: String): Kind = when {
        plain.startsWith("RPS2|") -> Kind.SIGNAL
        plain.startsWith("RPQ1|") -> Kind.PAIR_REQUEST
        plain.startsWith("RPA1|") -> Kind.PAIR_ACK
        plain.startsWith("RPI1|") -> Kind.INBOX
        else -> Kind.UNKNOWN
    }
}
