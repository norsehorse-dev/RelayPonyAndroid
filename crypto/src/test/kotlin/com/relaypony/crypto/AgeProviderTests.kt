package com.relaypony.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Phase 1 tests for [AgeProvider]. AgeProvider is a thin wrapper over AgePony's Age.encryptStream /
 * decryptStream, which are themselves byte-identity- and age-CLI-cross-impl-verified in
 * agepony-core's own suite (AgePayloadStreamTests / AgeStreamEndToEndTests). These tests therefore
 * focus on the seam: round-tripping through the CryptoProvider interface, recipient/identity
 * handling, QR encode/decode symmetry, and rejection of the wrong scheme or foreign handle types.
 */
class AgeProviderTests {
    private val provider = AgeProvider()
    private val rng = SecureRandom()

    private val sizes = listOf(0, 1, 100, 65_535, 65_536, 65_537, 200_000)

    @Test
    fun schemeId_isAge() {
        assertEquals(0x01.toByte(), provider.schemeId)
    }

    @Test
    fun roundTrip_throughInterface_acrossSizes() {
        val identity = provider.generateIdentity()
        val recipient = provider.recipientOf(identity)
        for (size in sizes) {
            val plaintext = ByteArray(size).also { rng.nextBytes(it) }
            val ct = ByteArrayOutputStream()
            provider.encryptStream(listOf(recipient), ByteArrayInputStream(plaintext), ct)

            val pt = ByteArrayOutputStream()
            val sender = provider.decryptStream(identity, ByteArrayInputStream(ct.toByteArray()), pt)

            assertArrayEquals(plaintext, pt.toByteArray(), "round-trip mismatch at size=$size")
            assertNull(sender, "age carries no signature, so VerifiedSender must be null")
        }
    }

    @Test
    fun qrEncodeDecode_isSymmetric_andDecryptsEndToEnd() {
        val identity = provider.generateIdentity()
        val advertised = provider.recipientOf(identity)

        // A device encodes its recipient into a QR; the sender scans and decodes it.
        val qr = provider.recipientToQr(advertised)
        val scanned = provider.recipientFromQr(qr)

        val plaintext = "scanned-recipient path".toByteArray()
        val ct = ByteArrayOutputStream()
        provider.encryptStream(listOf(scanned), ByteArrayInputStream(plaintext), ct)

        val pt = ByteArrayOutputStream()
        provider.decryptStream(identity, ByteArrayInputStream(ct.toByteArray()), pt)
        assertArrayEquals(plaintext, pt.toByteArray())
    }

    @Test
    fun multiRecipient_eachIdentityCanDecrypt() {
        val a = provider.generateIdentity()
        val b = provider.generateIdentity()
        val plaintext = "group send".toByteArray()

        val ct = ByteArrayOutputStream()
        provider.encryptStream(
            listOf(provider.recipientOf(a), provider.recipientOf(b)),
            ByteArrayInputStream(plaintext),
            ct,
        )
        val bytes = ct.toByteArray()

        val ptA = ByteArrayOutputStream()
        provider.decryptStream(a, ByteArrayInputStream(bytes), ptA)
        val ptB = ByteArrayOutputStream()
        provider.decryptStream(b, ByteArrayInputStream(bytes), ptB)

        assertArrayEquals(plaintext, ptA.toByteArray())
        assertArrayEquals(plaintext, ptB.toByteArray())
    }

    @Test
    fun strangerCannotDecrypt() {
        val intended = provider.generateIdentity()
        val stranger = provider.generateIdentity()
        val ct = ByteArrayOutputStream()
        provider.encryptStream(
            listOf(provider.recipientOf(intended)),
            ByteArrayInputStream("private".toByteArray()),
            ct,
        )
        assertThrows(com.agepony.core.Age.NoMatchingIdentityException::class.java) {
            provider.decryptStream(stranger, ByteArrayInputStream(ct.toByteArray()), ByteArrayOutputStream())
        }
    }

    @Test
    fun stringFactories_matchGeneratedKeys() {
        val identity = provider.generateIdentity() as AgeIdentityHandle
        val secret = identity.identity.toBech32()
        val age1 = identity.identity.let { com.agepony.core.recipients.X25519Recipient(it.publicKey).toBech32() }

        // Reload from strings and confirm the round-trip still works.
        val reloadedId = provider.identityFromString(secret)
        val reloadedRecipient = provider.recipientFromString(age1)

        val plaintext = "reloaded from strings".toByteArray()
        val ct = ByteArrayOutputStream()
        provider.encryptStream(listOf(reloadedRecipient), ByteArrayInputStream(plaintext), ct)
        val pt = ByteArrayOutputStream()
        provider.decryptStream(reloadedId, ByteArrayInputStream(ct.toByteArray()), pt)
        assertArrayEquals(plaintext, pt.toByteArray())
    }

    @Test
    fun encrypt_rejectsEmptyRecipients() {
        assertThrows(IllegalArgumentException::class.java) {
            provider.encryptStream(emptyList(), ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        }
    }

    @Test
    fun foreignRecipientType_isRejected() {
        val foreign = object : Recipient {}
        assertThrows(IllegalArgumentException::class.java) {
            provider.encryptStream(listOf(foreign), ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        }
    }
}
