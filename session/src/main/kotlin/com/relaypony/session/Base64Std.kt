package com.relaypony.session

/**
 * Standard base64 (RFC 4648 section 4) with padding and no line breaks, the same bytes as
 * android.util.Base64 with NO_WRAP. Pure Kotlin so the session module has no Android dependency
 * and runs on every API level (java.util.Base64 needs API 26). Used for relay payloads.
 */
object Base64Std {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8) or (bytes[i + 2].toInt() and 0xff)
            sb.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63]).append(ALPHABET[(n ushr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xff) shl 16
                sb.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63]).append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
                sb.append(ALPHABET[n ushr 18]).append(ALPHABET[(n ushr 12) and 63]).append(ALPHABET[(n ushr 6) and 63]).append('=')
            }
        }
        return sb.toString()
    }

    /**
     * Decodes standard base64. Whitespace is skipped and padding is optional, as android.util.Base64
     * allowed. Throws IllegalArgumentException on any other character, data after padding, or a
     * length that can't be base64.
     */
    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4)
        var buf = 0
        var bits = 0
        var chars = 0
        var padding = 0
        for (c in text) {
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue
            if (c == '=') { padding++; continue }
            require(padding == 0) { "base64 data after padding" }
            val v = if (c.code < 128) DECODE[c.code] else -1
            require(v >= 0) { "bad base64 character" }
            buf = (buf shl 6) or v
            bits += 6
            chars++
            if (bits >= 8) {
                bits -= 8
                out.write((buf ushr bits) and 0xff)
            }
        }
        require(chars % 4 != 1) { "bad base64 length" }
        require(padding <= 2) { "bad base64 padding" }
        return out.toByteArray()
    }
}
