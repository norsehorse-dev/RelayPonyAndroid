package com.relaypony.session

import com.relaypony.crypto.AgeProvider
import com.relaypony.transport.WireProtocol
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** Authenticated HELLO and paired-only receive (PROTOCOL_v3.md section 9). */
class HelloAuthTests {
    private val provider = AgeProvider()
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val scalarA = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
    private val scalarB = hex("201f1e1d1c1b1a191817161514131211100f0e0d0c0b0a090807060504030201")
    private val handleA = "age1q73he0q5yzfu3d64msd3p6rvksnrwjk3d2598mgtmlqt9wrdr37q2vrn72"
    private val handleB = "age1p4uevq8kl7hw9cfpu6u00gzace58fdgakvgz6rt377v6p895c3ss5ae3ad"
    private val at = 1791000000000L

    @Test
    fun helloTail_matchesReference_andVerifiesOnB() {
        val out = ByteArrayOutputStream()
        WireProtocol.writeHelloV2(out, 0x01, "Test Phone 8", handleA, 0) { body ->
            HelloAuth.tail(scalarA, handleA, handleB, body, at)
        }
        val payload = out.toByteArray().copyOfRange(5, out.size())
        val body = "0201000c546573742050686f6e652038003e616765317137336865307135797a6675336436346d736433703672766b736e72776a6b3364323539386d67746d6c717439777264723337713276726e373200020000"
        val tail = "52504831000001a0ffeb3600bf4a86a86d61ad8492730529a0f0379070a491816849126a0dc78598c94196a0"
        assertEquals(body + tail, hexOf(payload))

        val hello = WireProtocol.parseHello(payload)
        assertEquals("Test Phone 8", hello.deviceName)
        assertEquals(handleA, hello.recipientHandle)
        assertNotNull(hello.auth)
        val auth = hello.auth!!
        assertEquals(at, auth.timeMs)
        assertArrayEquals(hex(body), auth.body)
        assertTrue(HelloAuth.verify(scalarB, handleB, handleA, auth, at + 60_000))
        // Too old, or claimed by another handle: no.
        assertFalse(HelloAuth.verify(scalarB, handleB, handleA, auth, at + HelloAuth.MAX_SKEW_MS + 1))
        assertFalse(HelloAuth.verify(scalarB, handleB, handleB, auth, at))
    }

    @Test
    fun helloWithoutTail_hasNoAuth_andRefusePayloadRoundTrips() {
        val out = ByteArrayOutputStream()
        WireProtocol.writeHelloV2(out, 0x01, "Old Phone", handleA, 0)
        assertNull(WireProtocol.parseHello(out.toByteArray().copyOfRange(5, out.size())).auth)
        assertEquals("RPR1|unpaired", String(WireProtocol.refusePayload("unpaired"), Charsets.UTF_8))
        assertEquals("unpaired", WireProtocol.refuseReason(WireProtocol.refusePayload("unpaired")))
        assertEquals("unknown", WireProtocol.refuseReason("nope".toByteArray()))
    }

    /** One v2 session over pipes. Returns the receiver's result or error, and the sender's error. */
    private fun session(gate: (WireProtocol.Hello) -> String?, signed: Boolean): Pair<Result<ReceiveResult>, Throwable?> {
        val receiver = provider.generateIdentity()
        val receiverHandle = String(provider.recipientToQr(provider.recipientOf(receiver)), Charsets.UTF_8)
        val sender = provider.generateIdentity()
        val senderHandle = String(provider.recipientToQr(provider.recipientOf(sender)), Charsets.UTF_8)
        val data = ByteArray(10_000) { it.toByte() }
        val files = listOf(OutgoingFile("f.bin", "application/octet-stream", data.size.toLong()) { ByteArrayInputStream(data) })

        val s2rIn = PipedInputStream(1 shl 20); val s2rOut = PipedOutputStream(s2rIn)
        val r2sIn = PipedInputStream(1 shl 20); val r2sOut = PipedOutputStream(r2sIn)
        var received: Result<ReceiveResult>? = null
        val t = Thread {
            received = runCatching {
                Session.receive(
                    provider, receiver, s2rIn,
                    sink = { ByteArrayOutputStream() },
                    reverseOut = r2sOut,
                    recipientHandle = receiverHandle,
                    gate = gate,
                )
            }
            runCatching { r2sOut.close() }
        }
        t.start()
        val sendError = runCatching {
            Session.send(
                provider, listOf(provider.recipientOf(receiver)), "Sender", senderHandle, files, s2rOut,
                peerMaxWire = 2,
                reverseIn = r2sIn,
                helloAuth = if (signed) HelloAuth.signer(provider.scalarOf(sender), senderHandle, receiverHandle) else null,
            )
        }.exceptionOrNull()
        runCatching { s2rOut.close() }
        t.join(10_000)
        return received!! to sendError
    }

    @Test
    fun refusedSender_getsTheReason_andNothingIsReceived() {
        val (received, sendError) = session(gate = { "unpaired" }, signed = true)
        val refused = received.exceptionOrNull() as RefusedException
        assertEquals("unpaired", refused.reason)
        assertEquals("Sender", refused.senderName)
        assertEquals("unpaired", (sendError as RefusedException).reason)
    }

    @Test
    fun acceptedSender_isReceived_withAVerifiableTag() {
        var verified = false
        var hadTail = false
        val (received, sendError) = session(gate = { hello -> hadTail = hello.auth != null; verified = hadTail; null }, signed = true)
        assertNull(sendError)
        assertEquals(1, received.getOrThrow().manifest.files.size)
        assertTrue(hadTail && verified)
    }

    @Test
    fun unsignedSender_carriesNoTail() {
        var sawTail = true
        val (received, _) = session(gate = { hello -> sawTail = hello.auth != null; null }, signed = false)
        assertTrue(received.isSuccess)
        assertFalse(sawTail)
    }

    @Test
    fun tamperedBody_failsVerification() {
        val out = ByteArrayOutputStream()
        WireProtocol.writeHelloV2(out, 0x01, "Test Phone 8", handleA, 0) { body -> HelloAuth.tail(scalarA, handleA, handleB, body, at) }
        val payload = out.toByteArray().copyOfRange(5, out.size())
        payload[6] = 'X'.code.toByte()     // the name, as an attacker who relabels the device would
        val hello = WireProtocol.parseHello(payload)
        assertFalse(HelloAuth.verify(scalarB, handleB, handleA, hello.auth!!, at))
    }
}
