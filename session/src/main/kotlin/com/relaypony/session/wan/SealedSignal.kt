package com.relaypony.session.wan

import com.relaypony.crypto.CryptoProvider
import com.relaypony.crypto.Identity
import com.relaypony.crypto.PeerKey
import com.relaypony.transport.SignalBlob
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sealed signaling (4.0 P0, relay finding R1). Every WAN signaling blob between paired devices is
 * age-encrypted to the recipient's handle, so the relay and anyone polling see only an age file,
 * never the ICE candidates (IP addresses) inside. age stays the only encryption.
 *
 * age encryption is anonymous, so the plaintext also carries a MAC that proves the sender holds
 * the age secret behind its claimed `from` handle:
 *
 *   K_sig  = HKDF-SHA256(X25519(mySecret, peerPublic), salt = sorted handles joined by 0x00,
 *                        info = "relaypony/signal/mac/v1", 32)
 *   inner  = "RPS2|<kind>|<from>|<to>|<sessionNonceHex>|<candidates>"
 *   tag    = HMAC-SHA256(K_sig, UTF-8(inner))
 *   plain  = inner + "|" + hex(tag)
 *   sealed = age_encrypt(to: <to>, UTF-8(plain))      (binary age file, carried base64 by the relay)
 *
 * Both sides derive the same K_sig from their own secret and the other's handle, so no extra
 * exchange is needed and the tag is deniable. Byte parity with iOS is pinned by the vector in
 * SealedSignalTests.
 */
object SealedSignal {
    const val PREFIX = "RPS2"

    /** Magic bytes every age v1 file starts with; how a sealed relay payload is recognised. */
    val AGE_MAGIC: ByteArray = "age-encryption.org/v1".toByteArray(Charsets.US_ASCII)

    /** Largest sealed blob accepted before decrypting. Real ones are well under 2 KiB. */
    const val MAX_SEALED_BYTES: Int = 16 * 1024

    class SealException(message: String) : Exception(message)

    fun isSealed(bytes: ByteArray): Boolean =
        bytes.size >= AGE_MAGIC.size && AGE_MAGIC.indices.all { bytes[it] == AGE_MAGIC[it] }

    /** The text the MAC covers. Exposed for the cross-platform vector test. */
    fun inner(blob: SignalBlob): String {
        val nonceHex = blob.sessionNonce?.let { hex(it) } ?: ""
        return listOf(PREFIX, blob.kind.wire, blob.from, blob.to, nonceHex, blob.candidates.joinToString(","))
            .joinToString("|")
    }

    fun tag(key: ByteArray, inner: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(inner.toByteArray(Charsets.UTF_8))
    }

    /** Seal [blob] (whose `from` must be [myHandle]) to its `to` handle. */
    fun seal(provider: CryptoProvider, blob: SignalBlob, myScalar: ByteArray, myHandle: String): ByteArray {
        require(blob.from == myHandle) { "can only seal blobs from this device" }
        val key = PeerKey.deriveFromHandles(myScalar, myHandle, blob.to, PeerKey.SIGNAL_MAC_INFO)
        val inner = inner(blob)
        val plain = inner + "|" + hex(tag(key, inner))
        val recipient = provider.recipientFromQr(blob.to.toByteArray(Charsets.UTF_8))
        val out = ByteArrayOutputStream()
        provider.encryptStream(listOf(recipient), ByteArrayInputStream(plain.toByteArray(Charsets.UTF_8)), out)
        return out.toByteArray()
    }

    /**
     * Decrypt and authenticate a sealed blob addressed to [myHandle]. Throws [SealException] (or
     * the provider's decrypt error) on anything wrong: not for us, malformed, or a bad tag. A blob
     * that opens has a `from` proven by the tag, so callers can trust it for the pin check.
     */
    fun open(
        provider: CryptoProvider,
        identity: Identity,
        sealed: ByteArray,
        myScalar: ByteArray,
        myHandle: String,
    ): SignalBlob {
        if (sealed.size > MAX_SEALED_BYTES) throw SealException("sealed blob too large")
        val out = ByteArrayOutputStream()
        provider.decryptStream(identity, ByteArrayInputStream(sealed), out)
        return parse(String(out.toByteArray(), Charsets.UTF_8), myScalar, myHandle)
    }

    /**
     * Authenticate an already-decrypted `RPS2` line (for callers that opened the age file
     * themselves to dispatch on its prefix, PROTOCOL_v3.md section 6.1).
     */
    fun parse(plain: String, myScalar: ByteArray, myHandle: String): SignalBlob {
        val cut = plain.lastIndexOf('|')
        if (cut < 0) throw SealException("malformed sealed blob")
        val inner = plain.substring(0, cut)
        val tagHex = plain.substring(cut + 1)
        val parts = inner.split("|")
        if (parts.size != 6 || parts[0] != PREFIX) throw SealException("malformed sealed blob")
        val kind = SignalBlob.Kind.fromWire(parts[1]) ?: throw SealException("bad kind")
        val from = parts[2]
        val to = parts[3]
        if (from.isEmpty() || to != myHandle) throw SealException("sealed blob not addressed to this device")
        if (tagHex.length != 64) throw SealException("bad tag")

        val key = runCatching {
            PeerKey.deriveFromHandles(myScalar, myHandle, from, PeerKey.SIGNAL_MAC_INFO)
        }.getOrElse { throw SealException("bad sender handle") }
        val expected = tag(key, inner)
        val got = runCatching { unhex(tagHex) }.getOrElse { throw SealException("bad tag") }
        if (!MessageDigest.isEqual(expected, got)) throw SealException("tag mismatch")

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
