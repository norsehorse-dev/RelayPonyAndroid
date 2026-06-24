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
}
