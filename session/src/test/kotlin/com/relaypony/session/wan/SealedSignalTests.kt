package com.relaypony.session.wan

import com.relaypony.crypto.AgeProvider
import com.relaypony.crypto.PeerKey
import com.relaypony.transport.SignalBlob
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SealedSignalTests {
    private val provider = AgeProvider()

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class Dev(val provider: AgeProvider) {
        val identity = provider.generateIdentity()
        val handle = String(provider.recipientToQr(provider.recipientOf(identity)), Charsets.UTF_8)
        val scalar = provider.scalarOf(identity)
    }

    private fun offer(from: String, to: String) = SignalBlob(
        SignalBlob.Kind.OFFER, from, to,
        sessionNonce = ByteArray(16) { it.toByte() },
        candidates = listOf("203.0.113.7:40000", "192.168.1.20:40000"),
    )

    /** Cross-platform vector (same scalars/handles as PeerKeyTests). Mirror in iOS. */
    @Test
    fun tag_matchesCrossPlatformVector() {
        val scalarA = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
        val handleA = "age1q73he0q5yzfu3d64msd3p6rvksnrwjk3d2598mgtmlqt9wrdr37q2vrn72"
        val handleB = "age1p4uevq8kl7hw9cfpu6u00gzace58fdgakvgz6rt377v6p895c3ss5ae3ad"
        val inner = SealedSignal.inner(offer(handleA, handleB))
        assertEquals(
            "RPS2|offer|$handleA|$handleB|000102030405060708090a0b0c0d0e0f|203.0.113.7:40000,192.168.1.20:40000",
            inner,
        )
        val key = PeerKey.deriveFromHandles(scalarA, handleA, handleB, PeerKey.SIGNAL_MAC_INFO)
        assertArrayEquals(
            hex("104c92838299614b57ddd8c799389bf3ac79979024511d4dd33163933dbe9b2a"),
            SealedSignal.tag(key, inner),
        )
    }

    @Test
    fun sealOpen_roundTrips() {
        val a = Dev(provider); val b = Dev(provider)
        val sealed = SealedSignal.seal(provider, offer(a.handle, b.handle), a.scalar, a.handle)
        assertTrue(SealedSignal.isSealed(sealed))
        val opened = SealedSignal.open(provider, b.identity, sealed, b.scalar, b.handle)
        assertEquals(SignalBlob.Kind.OFFER, opened.kind)
        assertEquals(a.handle, opened.from)
        assertEquals(b.handle, opened.to)
        assertArrayEquals(ByteArray(16) { it.toByte() }, opened.sessionNonce)
        assertEquals(listOf("203.0.113.7:40000", "192.168.1.20:40000"), opened.candidates)
    }

    @Test
    fun iceWithoutNonce_roundTrips() {
        val a = Dev(provider); val b = Dev(provider)
        val ice = SignalBlob(SignalBlob.Kind.ICE, a.handle, b.handle, null, listOf("198.51.100.4:5000"))
        val opened = SealedSignal.open(provider, b.identity, SealedSignal.seal(provider, ice, a.scalar, a.handle), b.scalar, b.handle)
        assertEquals(null, opened.sessionNonce)
        assertEquals(listOf("198.51.100.4:5000"), opened.candidates)
    }

    @Test
    fun candidatesAreNotVisibleInSealedBytes() {
        val a = Dev(provider); val b = Dev(provider)
        val sealed = SealedSignal.seal(provider, offer(a.handle, b.handle), a.scalar, a.handle)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("203.0.113.7"))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains(a.handle))
    }

    @Test
    fun otherDeviceCannotOpen() {
        val a = Dev(provider); val b = Dev(provider); val c = Dev(provider)
        val sealed = SealedSignal.seal(provider, offer(a.handle, b.handle), a.scalar, a.handle)
        assertTrue(runCatching { SealedSignal.open(provider, c.identity, sealed, c.scalar, c.handle) }.isFailure)
    }

    @Test
    fun forgedFromIsRejected() {
        // C knows A's and B's public handles, so it can age-encrypt to B and claim to be A. It
        // can't produce A's tag, because K_sig needs A's or B's secret.
        val a = Dev(provider); val b = Dev(provider); val c = Dev(provider)
        val inner = SealedSignal.inner(offer(a.handle, b.handle))
        val wrongKey = PeerKey.deriveFromHandles(c.scalar, c.handle, b.handle, PeerKey.SIGNAL_MAC_INFO)
        val plain = inner + "|" + SealedSignal.tag(wrongKey, inner).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val out = ByteArrayOutputStream()
        provider.encryptStream(listOf(provider.recipientOf(b.identity)), ByteArrayInputStream(plain.toByteArray()), out)
        assertTrue(runCatching { SealedSignal.open(provider, b.identity, out.toByteArray(), b.scalar, b.handle) }.isFailure)
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val a = Dev(provider); val b = Dev(provider)
        val sealed = SealedSignal.seal(provider, offer(a.handle, b.handle), a.scalar, a.handle)
        sealed[sealed.size - 5] = (sealed[sealed.size - 5].toInt() xor 1).toByte()
        assertTrue(runCatching { SealedSignal.open(provider, b.identity, sealed, b.scalar, b.handle) }.isFailure)
    }

    @Test
    fun plaintextLegacyBlobIsNotSealed() {
        val line = offer("age1x", "age1y").encode().toByteArray()
        assertFalse(SealedSignal.isSealed(line))
    }
}
