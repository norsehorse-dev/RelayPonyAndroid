package com.relaypony.session

import com.relaypony.crypto.AgeProvider
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 21 (B2): the four-way handshake matrix — v1/v2 sender x v1/v2 receiver — run as a real
 * interactive session over two java.io pipes on two threads. Proves files round-trip byte-for-byte
 * in every combination, and that the negotiated version is 2 only when both sides are v2 (else the
 * frozen v1 monologue).
 *
 * A build is "v2" iff its transport gives the session a reverse channel; the sender additionally
 * learns the peer's advertised max wire from discovery, so it is handed peerMaxWire = 2 only when
 * the receiver is v2. A v1 build has no reverse channel — the real gate that keeps it on v1.
 */
class HandshakeMatrixTests {

    private val rng = SecureRandom()
    private val provider = AgeProvider()
    private fun bytesOf(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    @Test fun matrix_v1v1() = runCombo(senderV2 = false, receiverV2 = false)
    @Test fun matrix_v1sender_v2receiver() = runCombo(senderV2 = false, receiverV2 = true)
    @Test fun matrix_v2sender_v1receiver() = runCombo(senderV2 = true, receiverV2 = false)
    @Test fun matrix_v2v2() = runCombo(senderV2 = true, receiverV2 = true)

    private fun runCombo(senderV2: Boolean, receiverV2: Boolean) {
        val identity = provider.generateIdentity()
        val recipient = provider.recipientOf(identity)
        val receiverHandle = String(provider.recipientToQr(recipient), Charsets.UTF_8)

        val contents = linkedMapOf(
            "empty.bin" to bytesOf(0),
            "big.bin" to bytesOf(200_000),      // multi-chunk
            "edge.bin" to bytesOf(65_536),      // exact chunk boundary
        )
        val files = contents.map { (name, data) ->
            OutgoingFile(name, "application/octet-stream", data.size.toLong()) { ByteArrayInputStream(data) }
        }

        val s2rIn = PipedInputStream(1 shl 20)          // sender -> receiver
        val s2rOut = PipedOutputStream(s2rIn)
        val r2sIn = PipedInputStream(1 shl 20)          // receiver -> sender
        val r2sOut = PipedOutputStream(r2sIn)

        val received = LinkedHashMap<String, ByteArrayOutputStream>()
        val senderVersion = AtomicInteger(0)
        val receiverVersion = AtomicInteger(0)
        val receiveError = arrayOfNulls<Throwable>(1)

        val receiverThread = Thread {
            try {
                Session.receive(
                    provider, identity, s2rIn,
                    sink = { entry -> ByteArrayOutputStream().also { received[entry.name] = it } },
                    reverseOut = if (receiverV2) r2sOut else null,   // a v1 build has no reverse channel
                    deviceName = "Receiver",
                    recipientHandle = receiverHandle,
                    onNegotiated = { v, _ -> receiverVersion.set(v) },
                )
            } catch (t: Throwable) {
                receiveError[0] = t
            }
        }
        receiverThread.start()

        Session.send(
            provider, listOf(recipient), "Sender", "age1sender", files, s2rOut,
            peerMaxWire = if (receiverV2) 2 else 1,        // what the sender learned from discovery
            reverseIn = if (senderV2) r2sIn else null,      // a v1 build has no reverse channel
            onNegotiated = { v, _ -> senderVersion.set(v) },
        )

        receiverThread.join(10_000)
        s2rOut.close(); r2sOut.close()

        assertNull(receiveError[0], "receive failed ($senderV2,$receiverV2): ${receiveError[0]}")
        val expected = if (senderV2 && receiverV2) 2 else 1
        assertEquals(expected, senderVersion.get(), "sender version ($senderV2,$receiverV2)")
        assertEquals(expected, receiverVersion.get(), "receiver version ($senderV2,$receiverV2)")
        for ((name, data) in contents) {
            assertArrayEquals(data, received[name]!!.toByteArray(), "file $name mismatch ($senderV2,$receiverV2)")
        }
    }
}
