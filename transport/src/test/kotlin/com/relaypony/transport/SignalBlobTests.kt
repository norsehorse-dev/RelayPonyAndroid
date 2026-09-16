package com.relaypony.transport

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Cross-platform vector for [SignalBlob]. The same strings are asserted by the iOS SignalBlobTests, so
 * the paste blob a phone shows scans/pastes on the other platform. Deterministic: sessionNonce = bytes
 * 0x00..0x0f, two candidates.
 */
class SignalBlobTests {
    private val nonce = ByteArray(16) { it.toByte() }
    private val offerString =
        "RPS1|offer|age1aaaa|age1bbbb|000102030405060708090a0b0c0d0e0f|192.168.1.5:51820,203.0.113.9:24170"
    private val iceString = "RPS1|ice|age1aaaa|age1bbbb||203.0.113.9:24171"

    @Test
    fun offerEncodesToVector() {
        val b = SignalBlob(
            SignalBlob.Kind.OFFER, "age1aaaa", "age1bbbb", nonce,
            listOf("192.168.1.5:51820", "203.0.113.9:24170"),
        )
        assertEquals(offerString, b.encode())
    }

    @Test
    fun offerRoundTrips() {
        val decoded = SignalBlob.decode(offerString)
        assertEquals(SignalBlob.Kind.OFFER, decoded.kind)
        assertEquals("age1aaaa", decoded.from)
        assertEquals("age1bbbb", decoded.to)
        assertArrayEquals(nonce, decoded.sessionNonce)
        assertEquals(listOf("192.168.1.5:51820", "203.0.113.9:24170"), decoded.candidates)
        assertEquals(offerString, decoded.encode())
    }

    @Test
    fun iceEncodesAndDecodes() {
        val b = SignalBlob(SignalBlob.Kind.ICE, "age1aaaa", "age1bbbb", null, listOf("203.0.113.9:24171"))
        assertEquals(iceString, b.encode())
        val decoded = SignalBlob.decode(iceString)
        assertNull(decoded.sessionNonce)
        assertEquals(listOf("203.0.113.9:24171"), decoded.candidates)
    }

    @Test
    fun highByteNonceHexIsUnsigned() {
        val b = SignalBlob(SignalBlob.Kind.OFFER, "age1aaaa", "age1bbbb", byteArrayOf(0xff.toByte(), 0x00, 0x80.toByte(), 0x7f))
        assertEquals("RPS1|offer|age1aaaa|age1bbbb|ff00807f|", b.encode())
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0x00, 0x80.toByte(), 0x7f), SignalBlob.decode(b.encode()).sessionNonce)
    }

    @Test
    fun rejectsMalformed() {
        assertThrows(IllegalArgumentException::class.java) { SignalBlob.decode("not a blob") }
        assertThrows(IllegalArgumentException::class.java) { SignalBlob.decode("RPS1|bogus|a|b||") }
    }
}
