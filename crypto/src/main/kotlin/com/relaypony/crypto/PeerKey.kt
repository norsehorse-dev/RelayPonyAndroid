package com.relaypony.crypto

import com.agepony.core.bech32.Bech32
import com.agepony.core.crypto.HKDF
import com.agepony.core.crypto.X25519Crypto

/**
 * Derives the stable 32-byte symmetric key PonyDirect needs per peer, from material RelayPony
 * already holds: this device's age X25519 secret and the peer's age X25519 public key (its pinned
 * age1... handle). The key is used only for PonyDirect's handshake and datagram MACs; it is never
 * an age key and is never stored.
 *
 *   K = HKDF-SHA256(
 *         ikm  = X25519(myScalar, peerPublic),
 *         salt = sorted(myHandle, peerHandle)[0] || 0x00 || sorted(...)[1],
 *         info = "relaypony/ponydirect/peerkey/v1",
 *         len  = 32)
 *
 * The handles are sorted before salting so both devices compute the same K regardless of which
 * side initiates, the same construction the SAS code already uses. Byte-parity with the iOS
 * (CryptoKit) implementation is fixed by the shared vector in PeerKeyTests.
 */
object PeerKey {
    private const val INFO = "relaypony/ponydirect/peerkey/v1"

    /** Derive from raw 32-byte X25519 material. [myHandle] / [peerHandle] salt the KDF only. */
    fun derive(
        myScalar: ByteArray,
        peerPublic: ByteArray,
        myHandle: String,
        peerHandle: String,
    ): ByteArray {
        require(myScalar.size == 32) { "X25519 scalar must be 32 bytes, got ${myScalar.size}" }
        require(peerPublic.size == 32) { "peer public key must be 32 bytes, got ${peerPublic.size}" }

        val shared = X25519Crypto.keyExchange(myScalar, peerPublic)
        require(!shared.all { it == 0.toByte() }) { "zero shared secret (low-order point)" }

        val lo = minOf(myHandle, peerHandle)
        val hi = maxOf(myHandle, peerHandle)
        val salt = lo.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + hi.toByteArray(Charsets.UTF_8)
        return HKDF.derive(shared, salt, INFO.toByteArray(Charsets.UTF_8), 32)
    }

    /** Convenience: decode the peer's age1... handle to its raw public key, then [derive]. */
    fun deriveFromHandles(myScalar: ByteArray, myHandle: String, peerHandle: String): ByteArray {
        val (hrp, peerPublic) = Bech32.decode(peerHandle)
        require(hrp == "age" && peerPublic.size == 32) { "peer handle is not an age1 public key" }
        return derive(myScalar, peerPublic, myHandle, peerHandle)
    }
}
