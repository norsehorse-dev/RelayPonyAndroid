package com.relaypony.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/**
 * The beacon listens on a broadcast port, which means it sees every other application's traffic on
 * the network. Parsing has to be unbothered by all of it, so these tests are as much about what the
 * decoder refuses as about what it accepts.
 */
class BeaconCodecTests {

    private val rng = SecureRandom()

    @Test
    fun announce_roundTrips() {
        val frame = Beacon.encodeAnnounce(Beacon.DEFAULT_TRANSFER_PORT, 2, "Kevin's Pixel", "age1abcdef")
        val decoded = Beacon.decode(frame) as Beacon.Message.Announce
        assertEquals(Beacon.DEFAULT_TRANSFER_PORT, decoded.tcpPort)
        assertEquals(2, decoded.maxWire)
        assertEquals("Kevin's Pixel", decoded.deviceName)
        assertEquals("age1abcdef", decoded.recipientHandle)
    }

    @Test
    fun probe_roundTrips() {
        assertTrue(Beacon.decode(Beacon.encodeProbe()) is Beacon.Message.Probe)
    }

    @Test
    fun frame_alwaysFitsOneDatagram() {
        // Device names come from Build.MODEL or a user setting and are not length-checked upstream.
        val frame = Beacon.encodeAnnounce(65535, 9, "x".repeat(500), "age1" + "y".repeat(200))
        assertTrue(frame.size <= Beacon.MAX_FRAME, "frame was ${frame.size} bytes")
    }

    @Test
    fun longName_truncatedWithoutBreakingUtf8() {
        val frame = Beacon.encodeAnnounce(1, 1, "é".repeat(300), "age1x")
        val decoded = Beacon.decode(frame) as Beacon.Message.Announce
        assertTrue(decoded.deviceName.isNotEmpty())
        assertTrue(decoded.deviceName.all { it == 'é' }, "truncation split a multi-byte character")
    }

    @Test
    fun foreignTraffic_isIgnoredNotMisparsed() {
        assertNull(Beacon.decode("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()))
        assertNull(Beacon.decode(ByteArray(0)))
        assertNull(Beacon.decode(ByteArray(Beacon.MAX_FRAME + 1)))
        assertNull(Beacon.decode("RPB2".toByteArray()))            // a future version's magic
        assertNull(Beacon.decode("RPB1".toByteArray()))            // right magic, no type byte
    }

    @Test
    fun truncatedAnnounce_isRejectedNotReadPastItsEnd() {
        val frame = Beacon.encodeAnnounce(Beacon.DEFAULT_TRANSFER_PORT, 1, "phone", "age1abcdef")
        for (cut in 1 until frame.size) {
            assertNull(Beacon.decode(frame, frame.size - cut), "accepted a frame cut by $cut bytes")
        }
    }

    @Test
    fun emptyHandle_isRejected() {
        // A peer we cannot encrypt to is useless; catching it here beats discovering it at send time.
        val handle = "age1abcdef"
        val frame = Beacon.encodeAnnounce(Beacon.DEFAULT_TRANSFER_PORT, 1, "phone", handle)
        val lenAt = frame.size - handle.length - 2
        val broken = frame.copyOf()
        broken[lenAt] = 0
        broken[lenAt + 1] = 0
        assertNull(Beacon.decode(broken, lenAt + 2))
    }

    @Test
    fun randomInput_neverThrows() {
        repeat(50_000) {
            val buf = ByteArray(rng.nextInt(96)).also { rng.nextBytes(it) }
            if (buf.size >= 5 && rng.nextBoolean()) "RPB1".toByteArray().copyInto(buf)
            Beacon.decode(buf)      // must return null or a Message — never throw
        }
    }

    @Test
    fun beaconPort_sitsAboveTheTransferPort() {
        assertEquals(Beacon.DEFAULT_TRANSFER_PORT + 1, Beacon.PORT)
    }
}
