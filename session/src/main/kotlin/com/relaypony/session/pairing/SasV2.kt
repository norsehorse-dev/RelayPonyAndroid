package com.relaypony.session.pairing

import java.security.MessageDigest

/**
 * SAS v2 (PROTOCOL_v3.md section 4.3): the 8-digit code both screens show during QR pairing, bound
 * to the QR shower's single-use nonce and the scanner's secret nonce, so it can't be ground offline.
 *
 *   digest = SHA-256("relaypony-sas-v2" 0 lo 0 hi 0 nonceA nonceB)
 *   code   = big-endian u64(digest[0..8]) mod 10^8
 *
 * `lo`/`hi` are the handles sorted; `nonceA` is always the QR shower's nonce, `nonceB` the scanner's.
 */
object SasV2 {
    fun digest(handleA: String, handleB: String, nonceA: ByteArray, nonceB: ByteArray): ByteArray {
        val lo = minOf(handleA, handleB)
        val hi = maxOf(handleA, handleB)
        val md = MessageDigest.getInstance("SHA-256")
        md.update("relaypony-sas-v2".toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(lo.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(hi.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(nonceA)
        md.update(nonceB)
        return md.digest()
    }

    /** The 8-digit code, zero-padded. */
    fun code(handleA: String, handleB: String, nonceA: ByteArray, nonceB: ByteArray): String {
        val d = digest(handleA, handleB, nonceA, nonceB)
        // Unsigned 64-bit value of the first 8 bytes. BigInteger rather than Long.remainderUnsigned,
        // which needs API 26.
        val v = java.math.BigInteger(1, d.copyOfRange(0, 8))
        return v.mod(java.math.BigInteger.valueOf(100_000_000L)).toString().padStart(8, '0')
    }

    /** "49127512" -> "4912 7512". */
    fun display(code: String): String = if (code.length == 8) code.substring(0, 4) + " " + code.substring(4) else code
}
