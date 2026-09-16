package com.relaypony.transport

/**
 * A copyable, serverless carrier for one PonyDirect WAN signaling message. It mirrors the fields of
 * PonyDirect's PonyDirectSignal (kind + candidates + sessionNonce) plus the from/to age handles used
 * to route it, and serializes to a single QR/paste-friendly line in the same RP-prefixed, pipe-
 * delimited style as the pairing QrPayload. This is the "no rendezvous server" path: one device shows
 * the blob, the user sends it over any channel they already have, the peer pastes it in.
 *
 *   RPS1|<kind>|<from>|<to>|<sessionNonceHex>|<candidate,candidate,...>
 *
 * sessionNonceHex is empty for ice; candidates carries the trickled ip:port for ice, the full list for
 * offer/answer. age1 handles and ip:port candidates contain neither '|' nor ',', so no escaping is
 * needed. The mapping to/from PonyDirectSignal lives in the PonyDirectSignaling conformer, so this
 * type has no dependency on the PonyDirect library and stays pure-JVM testable.
 */
class SignalBlob(
    val kind: Kind,
    val from: String,
    val to: String,
    val sessionNonce: ByteArray? = null,
    val candidates: List<String> = emptyList(),
) {
    enum class Kind(val wire: String) {
        OFFER("offer"), ANSWER("answer"), ICE("ice");

        companion object {
            fun fromWire(s: String): Kind? = values().firstOrNull { it.wire == s }
        }
    }

    fun encode(): String {
        val nonceHex = sessionNonce?.let { hex(it) } ?: ""
        return listOf(PREFIX, kind.wire, from, to, nonceHex, candidates.joinToString(",")).joinToString("|")
    }

    companion object {
        const val PREFIX = "RPS1"

        fun decode(text: String): SignalBlob {
            val parts = text.split("|")
            require(parts.size == 6) { "expected 6 fields, got ${parts.size}" }
            require(parts[0] == PREFIX) { "not an $PREFIX blob" }
            val kind = Kind.fromWire(parts[1]) ?: throw IllegalArgumentException("bad kind: ${parts[1]}")
            val from = parts[2]
            val to = parts[3]
            require(from.isNotEmpty() && to.isNotEmpty()) { "empty handle" }
            val nonce = if (parts[4].isEmpty()) null else unhex(parts[4])
            val candidates = if (parts[5].isEmpty()) emptyList() else parts[5].split(",")
            return SignalBlob(kind, from, to, nonce, candidates)
        }

        private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        private fun unhex(s: String): ByteArray {
            require(s.length % 2 == 0) { "odd hex length" }
            return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}
