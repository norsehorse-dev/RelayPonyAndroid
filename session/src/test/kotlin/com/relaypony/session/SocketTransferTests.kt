package com.relaypony.session

import com.relaypony.crypto.AgeProvider
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Proves a file transfers over a REAL TCP socket (localhost loopback) through the full Session
 * flow. This is the JVM-verifiable half of Phase 3; the mDNS/device half is exercised by the app
 * harness on hardware.
 */
class SocketTransferTests {
    private val provider = AgeProvider()
    private val rng = SecureRandom()

    @Test
    fun fileTransfersOverLoopbackSocket() {
        val identity = provider.generateIdentity()
        val recipient = provider.recipientOf(identity)
        val handle = String(provider.recipientToQr(recipient), Charsets.UTF_8)

        val payload = ByteArray(1_500_000).also { rng.nextBytes(it) }

        val server = ServerSocket(0)
        val port = server.localPort
        val received = AtomicReference<ByteArrayOutputStream>()
        val resultRef = AtomicReference<ReceiveResult>()
        val error = AtomicReference<Throwable>()

        val receiver = thread(name = "test-receiver") {
            try {
                server.use { srv ->
                    resultRef.set(
                        SocketTransfer.receiveOnceFrom(srv, provider, identity) { _ ->
                            ByteArrayOutputStream().also { received.set(it) }
                        }
                    )
                }
            } catch (t: Throwable) {
                error.set(t)
            }
        }

        val files = listOf(
            OutgoingFile("blob.bin", "application/octet-stream", payload.size.toLong()) {
                ByteArrayInputStream(payload)
            }
        )
        SocketTransfer.sendTo("127.0.0.1", port, provider, listOf(recipient), "sender", handle, files)

        receiver.join(10_000)
        assertNull(error.get(), "receiver threw: ${error.get()}")
        assertEquals("sender", resultRef.get().senderName)
        assertEquals(listOf("blob.bin"), resultRef.get().manifest.files.map { it.name })
        assertArrayEquals(payload, received.get().toByteArray())
    }
}
