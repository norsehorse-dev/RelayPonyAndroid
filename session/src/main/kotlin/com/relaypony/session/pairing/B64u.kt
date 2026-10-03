package com.relaypony.session.pairing

/**
 * base64url without padding (RFC 4648 section 5), pure Kotlin so it runs the same in JVM unit tests
 * and on every Android API level (java.util.Base64 needs API 26). Used for pair nonces and inbox ids.
 */
object B64u {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val DECODE = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8) or (bytes[i + 2].toInt() and 0xff)
            sb.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63]).append(ALPHABET[(n ushr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xff) shl 16
                sb.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63])
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
                sb.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63]).append(ALPHABET[(n ushr 6) and 63])
            }
        }
        return sb.toString()
    }

    /** Decodes, or throws IllegalArgumentException on any character outside the alphabet or a bad length. */
    fun decode(text: String): ByteArray {
        require(text.length % 4 != 1) { "bad base64url length" }
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (c in text) {
            val v = if (c.code < 128) DECODE[c.code] else -1
            require(v >= 0) { "bad base64url character" }
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf ushr bits) and 0xff)
            }
        }
        require(buf and ((1 shl bits) - 1) == 0) { "non-canonical base64url" }
        return out.toByteArray()
    }
}
