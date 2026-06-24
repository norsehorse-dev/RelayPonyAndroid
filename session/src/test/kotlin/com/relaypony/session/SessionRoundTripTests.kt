package com.relaypony.session

import com.relaypony.crypto.AgeProvider
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * Phase 2 done-conditions, driven over an in-memory loopback (sender writes the whole session to
 * a buffer; receiver reads it back). No sockets yet.
 */
class SessionRoundTripTests {
    private val rng = SecureRandom()
    private val provider = AgeProvider()

    private fun bytesOf(size: Int) = ByteArray(size).also { rng.nextBytes(it) }

    @Test
    fun fullSession_roundTripsMultipleFiles() {
        val identity = provider.generateIdentity()
        val recipient = provider.recipientOf(identity)
        val senderHandle = String(provider.recipientToQr(recipient), Charsets.UTF_8)

        val contents = linkedMapOf(
            "notes.txt" to bytesOf(0),
            "photo.jpg" to bytesOf(200_000),
            "clip.bin" to bytesOf(65_536),
        )
        val files = contents.map { (name, data) ->
            OutgoingFile(name, "application/octet-stream", data.size.toLong()) { ByteArrayInputStream(data) }
        }

        val wire = ByteArrayOutputStream()
        Session.send(provider, listOf(recipient), "Kevins-Phone", senderHandle, files, wire)

        val received = LinkedHashMap<String, ByteArrayOutputStream>()
        val result = Session.receive(
            provider,
            identity,
            ByteArrayInputStream(wire.toByteArray()),
        ) { entry -> ByteArrayOutputStream().also { received[entry.name] = it } }

        assertEquals("Kevins-Phone", result.senderName)
        assertEquals(contents.keys.toList(), result.manifest.files.map { it.name })
        for ((name, data) in contents) {
            assertArrayEquals(data, received[name]!!.toByteArray(), "file $name mismatch")
        }
    }

    @Test
    fun manifest_isEncryptedOnTheWire() {
        val identity = provider.generateIdentity()
        val recipient = provider.recipientOf(identity)
        val secretName = "tax-return-2025.pdf"
        val files = listOf(
            OutgoingFile(secretName, "application/pdf", 3) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        )

        val wire = ByteArrayOutputStream()
        Session.send(provider, listOf(recipient), "dev", "age1sender", files, wire)

        // The plaintext filename must not appear anywhere in the raw wire bytes.
        assertFalse(
            containsBytes(wire.toByteArray(), secretName.toByteArray(Charsets.UTF_8)),
            "plaintext filename leaked onto the wire",
        )
    }

    @Test
    fun unsupportedScheme_isRejected() {
        // Craft a HELLO advertising scheme 0x02 (OpenPGP); the age receiver must reject it.
        val wire = ByteArrayOutputStream()
        com.relaypony.transport.WireProtocol.writeHello(wire, 0x02, "dev", "handle")

        val identity = provider.generateIdentity()
        assertThrows(UnsupportedSchemeException::class.java) {
            Session.receive(provider, identity, ByteArrayInputStream(wire.toByteArray())) {
                OutputStream.nullOutputStream()
            }
        }
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
