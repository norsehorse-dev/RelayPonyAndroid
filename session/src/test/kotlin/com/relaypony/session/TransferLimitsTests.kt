package com.relaypony.session

import com.relaypony.crypto.AgeProvider
import com.relaypony.transport.WireProtocol
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** Audit 2.5: ceilings are enforced on bytes actually written, never on declared sizes. */
class TransferLimitsTests {
    private val rng = SecureRandom()
    private val provider = AgeProvider()

    private fun wireFor(files: List<OutgoingFile>): Pair<ByteArray, com.relaypony.crypto.Identity> {
        val identity = provider.generateIdentity()
        val recipient = provider.recipientOf(identity)
        val wire = ByteArrayOutputStream()
        Session.send(provider, listOf(recipient), "dev", "age1sender", files, wire)
        return wire.toByteArray() to identity
    }

    private fun file(name: String, data: ByteArray, declared: Long = data.size.toLong()) =
        OutgoingFile(name, "application/octet-stream", declared) { ByteArrayInputStream(data) }

    private fun bytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    @Test
    fun withinLimits_succeeds() {
        val data = bytes(100_000)
        val (wire, id) = wireFor(listOf(file("a.bin", data)))
        val out = ByteArrayOutputStream()
        Session.receive(provider, id, ByteArrayInputStream(wire), { out },
            limits = TransferLimits(maxFileBytes = 100_000, maxTransferBytes = 100_000))
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun fileOverCap_throws() {
        val (wire, id) = wireFor(listOf(file("a.bin", bytes(100_001))))
        assertThrows(TransferTooLargeException::class.java) {
            Session.receive(provider, id, ByteArrayInputStream(wire), { ByteArrayOutputStream() },
                limits = TransferLimits(maxFileBytes = 100_000))
        }
    }

    @Test
    fun declaredSizeIsNotTrusted() {
        // The manifest says 10 bytes; the body carries 50,000. The cap still trips on real bytes.
        val (wire, id) = wireFor(listOf(file("liar.bin", bytes(50_000), declared = 10)))
        assertThrows(TransferTooLargeException::class.java) {
            Session.receive(provider, id, ByteArrayInputStream(wire), { ByteArrayOutputStream() },
                limits = TransferLimits(maxFileBytes = 20_000))
        }
    }

    @Test
    fun runningTotalOverTransferCap_throws() {
        val files = (1..3).map { file("f$it.bin", bytes(40_000), declared = 1) }
        val (wire, id) = wireFor(files)
        assertThrows(TransferTooLargeException::class.java) {
            Session.receive(provider, id, ByteArrayInputStream(wire), { ByteArrayOutputStream() },
                limits = TransferLimits(maxFileBytes = 50_000, maxTransferBytes = 100_000))
        }
    }

    @Test
    fun declaredTotalOverFreeSpace_refusedBeforeAnyWrite() {
        val (wire, id) = wireFor(listOf(file("a.bin", bytes(1_000))))
        var opened = 0
        assertThrows(InsufficientSpaceException::class.java) {
            Session.receive(provider, id, ByteArrayInputStream(wire), { opened++; ByteArrayOutputStream() },
                limits = TransferLimits(freeSpaceMarginBytes = 500, freeBytes = { 1_200 }))
        }
        assertEquals(0, opened)
    }

    @Test
    fun manifestRejections() {
        val tooMany = Manifest((0..WireProtocol.MAX_MANIFEST_ENTRIES).map { FileEntry("f$it", 0, "x") })
        assertThrows(TransferTooLargeException::class.java) { TransferLimits().checkManifest(tooMany) }
        val negative = Manifest(listOf(FileEntry("f", -1, "x")))
        assertThrows(ProtocolException::class.java) { TransferLimits().checkManifest(negative) }
        val huge = Manifest(listOf(FileEntry("f", TransferLimits.MAX_FILE_BYTES + 1, "x")))
        assertThrows(TransferTooLargeException::class.java) { TransferLimits().checkManifest(huge) }
        val overTotal = Manifest(List(5) { FileEntry("f$it", TransferLimits.MAX_FILE_BYTES, "x") })
        assertThrows(TransferTooLargeException::class.java) { TransferLimits().checkManifest(overTotal) }
    }
}
