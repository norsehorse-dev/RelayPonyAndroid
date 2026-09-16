package com.relaypony.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Cross-platform parity vector for [PeerKey]. The same scalars, public keys, handles, and expected
 * K are asserted by the iOS PeerKeyTests, so Android (BouncyCastle) and iOS (CryptoKit) are proven
 * byte-identical before any networking is built. The vector is deterministic: scalarA = bytes
 * 0x01..0x20, scalarB = bytes 0x20..0x01; the age1 handles are the real Bech32 encodings of the
 * derived public keys.
 */
class PeerKeyTests {
    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val scalarA = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
    private val scalarB = hex("201f1e1d1c1b1a191817161514131211100f0e0d0c0b0a090807060504030201")
    private val pubA = hex("07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c")
    private val pubB = hex("0d799600f6ffaee2e121e6b8f7a05dc66874b51db3102d0d71f799a09cb4c461")
    private val handleA = "age1q73he0q5yzfu3d64msd3p6rvksnrwjk3d2598mgtmlqt9wrdr37q2vrn72"
    private val handleB = "age1p4uevq8kl7hw9cfpu6u00gzace58fdgakvgz6rt377v6p895c3ss5ae3ad"
    private val expectedK = hex("f1d5349d5c3c8050a403b43b99dc75297e840c161920b6a62cd77ab19bbe3850")

    @Test
    fun matchesCrossPlatformVector() {
        assertArrayEquals(expectedK, PeerKey.derive(scalarA, pubB, handleA, handleB))
    }

    @Test
    fun symmetricRegardlessOfInitiator() {
        val kFromA = PeerKey.derive(scalarA, pubB, handleA, handleB)
        val kFromB = PeerKey.derive(scalarB, pubA, handleB, handleA)
        assertArrayEquals(kFromA, kFromB)
        assertArrayEquals(expectedK, kFromB)
    }

    @Test
    fun deriveFromHandles_decodesPeerHandleAndMatches() {
        assertArrayEquals(expectedK, PeerKey.deriveFromHandles(scalarA, handleA, handleB))
    }

    @Test
    fun rejectsWrongScalarSize() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerKey.derive(ByteArray(31), pubB, handleA, handleB)
        }
    }
}
