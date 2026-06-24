package com.relaypony.session.pairing

import java.security.MessageDigest

/**
 * Short Authentication String: a no-camera pairing fallback. Both devices compute the same code
 * from the pair of handles and the users compare it aloud (a la Signal safety numbers). Symmetric
 * by construction (handles are sorted), so it does not matter which device shows it.
 */
object Sas {
    fun code(handleA: String, handleB: String, digits: Int = 6): String {
        require(digits in 1..9) { "digits out of range" }
        val sorted = listOf(handleA, handleB).sorted()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(sorted[0].toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(sorted[1].toByteArray(Charsets.UTF_8))
        val h = md.digest()
        val v = ((h[0].toInt() and 0xff).toLong() shl 24) or
            ((h[1].toInt() and 0xff).toLong() shl 16) or
            ((h[2].toInt() and 0xff).toLong() shl 8) or
            (h[3].toInt() and 0xff).toLong()
        val mod = (1..digits).fold(1L) { acc, _ -> acc * 10 }
        return (v % mod).toString().padStart(digits, '0')
    }
}
