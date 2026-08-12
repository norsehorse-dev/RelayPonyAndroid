package com.relaypony.transport

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

class WireCodecTests {
    private val rng = SecureRandom()

    @Test
    fun frame_roundTrips() {
        val out = ByteArrayOutputStream()
        val payload = ByteArray(1000).also { rng.nextBytes(it) }
        WireProtocol.writeFrame(out, WireProtocol.MANIFEST, payload)
        val frame = WireProtocol.readFrame(ByteArrayInputStream(out.toByteArray()))!!
        assertEquals(WireProtocol.MANIFEST, frame.type)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun readFrame_returnsNullAtEof() {
        assertNull(WireProtocol.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun hello_roundTrips_withUnicode() {
        val out = ByteArrayOutputStream()
        WireProtocol.writeHello(out, 0x01, "Kevin's Phone \u00e9\u00f1", "age1qwertyexample")
        val hello = WireProtocol.readHello(ByteArrayInputStream(out.toByteArray()))
        assertEquals(WireProtocol.WIRE_VERSION, hello.version)
        assertEquals(0x01.toByte(), hello.schemeId)
        assertEquals("Kevin's Phone \u00e9\u00f1", hello.deviceName)
        assertEquals("age1qwertyexample", hello.recipientHandle)
    }

    @Test
    fun truncatedFrame_throws() {
        // type + length say 100 bytes, but no payload follows.
        val bytes = byteArrayOf(WireProtocol.MANIFEST, 0, 0, 0, 100)
        assertThrows(WireProtocol.WireException::class.java) {
            WireProtocol.readFrame(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun chunkStreams_roundTripFileBody_acrossSizes() {
        val sizes = listOf(0, 1, 100, 65_535, 65_536, 65_537, 200_000)
        for (size in sizes) {
            val data = ByteArray(size).also { rng.nextBytes(it) }

            val wire = ByteArrayOutputStream()
            WireProtocol.writeFrame(wire, WireProtocol.FILE_BEGIN, ByteArray(0))
            val fcos = FrameChunkOutputStream(wire)
            // Write with an awkward split to prove the adapter is split-agnostic.
            var o = 0
            for (w in listOf(7, 1, 65_000, 5_000, size)) {
                val take = minOf(w, size - o)
                if (take > 0) { fcos.write(data, o, take); o += take }
            }
            fcos.finish()
            WireProtocol.writeFrame(wire, WireProtocol.FILE_END, ByteArray(0))
            WireProtocol.writeFrame(wire, WireProtocol.DONE, ByteArray(0))

            val input = ByteArrayInputStream(wire.toByteArray())
            val begin = WireProtocol.readFrame(input)!!
            assertEquals(WireProtocol.FILE_BEGIN, begin.type)
            val body = FrameChunkInputStream(input).readBytes()
            assertArrayEquals(data, body, "file body mismatch at size=$size")
            val done = WireProtocol.readFrame(input)!!
            assertEquals(WireProtocol.DONE, done.type, "DONE not positioned correctly at size=$size")
        }
    }

    // --- Wire v2 prep: version enforcement, the trailing-bytes pin, and mw parsing ---

    private fun helloBody(version: Int, name: String = "A", handle: String = "h", trailing: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(version and 0xff)
        body.write(0x01)
        val n = name.toByteArray(Charsets.UTF_8)
        body.write((n.size ushr 8) and 0xff); body.write(n.size and 0xff); body.write(n)
        val h = handle.toByteArray(Charsets.UTF_8)
        body.write((h.size ushr 8) and 0xff); body.write(h.size and 0xff); body.write(h)
        body.write(trailing)
        return body.toByteArray()
    }

    private fun frame(type: Byte, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        WireProtocol.writeFrame(out, type, payload)
        return out.toByteArray()
    }

    @Test
    fun hello_rejectsFutureVersion() {
        val wire = frame(WireProtocol.HELLO, helloBody(WireProtocol.MAX_WIRE_VERSION + 1))
        assertThrows(WireProtocol.WireException::class.java) {
            WireProtocol.readHello(ByteArrayInputStream(wire))
        }
    }

    @Test
    fun hello_rejectsVersionZero() {
        val wire = frame(WireProtocol.HELLO, helloBody(0))
        assertThrows(WireProtocol.WireException::class.java) {
            WireProtocol.readHello(ByteArrayInputStream(wire))
        }
    }

    @Test
    fun hello_toleratesTrailingBytes() {
        // Wire v2 will append capability bytes after the handle; this pin keeps that extension safe.
        val wire = frame(
            WireProtocol.HELLO,
            helloBody(WireProtocol.WIRE_VERSION, "Kevin", "age1x", byteArrayOf(0xAA.toByte(), 0xBB.toByte())),
        )
        val hello = WireProtocol.readHello(ByteArrayInputStream(wire))
        assertEquals(WireProtocol.WIRE_VERSION, hello.version)
        assertEquals("Kevin", hello.deviceName)
        assertEquals("age1x", hello.recipientHandle)
    }

    @Test
    fun helloV2_roundTripsCaps() {
        val caps = WireProtocol.CAP_WANT_ACK_FLOW or WireProtocol.CAP_RESUME
        val out = ByteArrayOutputStream()
        WireProtocol.writeHelloV2(out, 0x01, "Mac", "age1z", caps)
        val hello = WireProtocol.readHello(ByteArrayInputStream(out.toByteArray()))
        assertEquals(2, hello.version)                 // version 2 is now accepted
        assertEquals(0x01.toByte(), hello.schemeId)
        assertEquals("Mac", hello.deviceName)
        assertEquals("age1z", hello.recipientHandle)
        assertEquals(caps, hello.caps)
    }

    @Test
    fun readHello_v1HasNoCaps() {
        val out = ByteArrayOutputStream()
        WireProtocol.writeHello(out, 0x01, "Old", "age1old")
        val hello = WireProtocol.readHello(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, hello.version)
        assertEquals(0, hello.caps)
    }

    @Test
    fun helloV2_matchesSpecVector() {
        // PROTOCOL_v2_draft.md V-2: name "A", handle "h", caps bits 0+1 -> little-endian 03 00.
        val caps = WireProtocol.CAP_WANT_ACK_FLOW or WireProtocol.CAP_RESUME
        val out = ByteArrayOutputStream()
        WireProtocol.writeHelloV2(out, 0x01, "A", "h", caps)
        val expected = byteArrayOf(
            0x01, 0x00, 0x00, 0x00, 0x0C,      // type HELLO, u32 length 12
            0x02, 0x01,                         // version 2, scheme 1
            0x00, 0x01, 0x41,                   // nameLen 1, "A"
            0x00, 0x01, 0x68,                   // handleLen 1, "h"
            0x00, 0x02, 0x03, 0x00,             // capsLen 2, caps 03 00
        )
        assertArrayEquals(expected, out.toByteArray())
    }

    @Test
    fun maxWire_parsesAndDefaults() {
        assertEquals(1, WireProtocol.parseMaxWire(null))
        assertEquals(1, WireProtocol.parseMaxWire("abc"))
        assertEquals(1, WireProtocol.parseMaxWire("0"))
        assertEquals(1, WireProtocol.parseMaxWire("-3"))
        assertEquals(2, WireProtocol.parseMaxWire("2"))
        assertEquals(WireProtocol.WIRE_VERSION, WireProtocol.parseMaxWire(WireProtocol.WIRE_VERSION.toString()))
    }
}
