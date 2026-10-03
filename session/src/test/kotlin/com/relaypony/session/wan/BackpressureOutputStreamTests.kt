package com.relaypony.session.wan

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException

/** The WAN send's backpressure: bytes pass through in order, memory is capped, a dead peer fails. */
class BackpressureOutputStreamTests {

    /** A stream that "acknowledges" [drainPerPoll] bytes each time the writer waits. */
    private class FakeStream(val drainPerPoll: Long) {
        val received = ByteArrayOutputStream()
        var held = 0L
        var peak = 0L
        fun write(b: ByteArray) { received.write(b); held += b.size; peak = maxOf(peak, held) }
        fun drain() { held = maxOf(0L, held - drainPerPoll) }
    }

    @Test
    fun bytesArriveInOrder_andHeldBytesStayNearTheHighWaterMark() {
        val s = FakeStream(drainPerPoll = 32 * 1024)
        var t = 0L
        val out = BackpressureOutputStream(
            sink = s::write, buffered = { s.held },
            highWater = 256 * 1024, chunk = 16 * 1024,
            clock = { t }, pause = { t += it; s.drain() },
        )
        val data = ByteArray(5_000_000) { (it * 7 % 251).toByte() }
        var off = 0
        while (off < data.size) { val n = minOf(10_007, data.size - off); out.write(data, off, n); off += n }
        out.close()
        assertArrayEquals(data, s.received.toByteArray())
        assertEquals(data.size.toLong(), out.written)
        assertTrue(s.peak <= 256 * 1024 + 16 * 1024, "held ${s.peak}")
    }

    @Test
    fun aPeerThatStopsAcknowledging_failsTheWrite() {
        val s = FakeStream(drainPerPoll = 0)
        var t = 0L
        val out = BackpressureOutputStream(
            sink = s::write, buffered = { s.held },
            highWater = 64 * 1024, stallMs = 5_000, chunk = 16 * 1024,
            clock = { t }, pause = { t += it },
        )
        val e = assertThrows(IOException::class.java) { out.write(ByteArray(1_000_000)) }
        assertTrue(e !is InterruptedIOException)
        assertTrue(t >= 5_000)
    }

    @Test
    fun cancellingEndsTheWaitAtOnce() {
        val s = FakeStream(drainPerPoll = 0)
        var cancelled = false
        var t = 0L
        val out = BackpressureOutputStream(
            sink = s::write, buffered = { s.held }, cancelled = { cancelled },
            highWater = 64 * 1024, chunk = 16 * 1024,
            clock = { t }, pause = { t += it; if (t > 100) cancelled = true },
        )
        assertThrows(InterruptedIOException::class.java) { out.write(ByteArray(1_000_000)) }
        assertTrue(t < 1_000)
    }

    @Test
    fun slowButSteadyProgress_isNotAStall() {
        val s = FakeStream(drainPerPoll = 1)          // one byte per poll: slow, but moving
        var t = 0L
        val out = BackpressureOutputStream(
            sink = s::write, buffered = { s.held },
            highWater = 1024, stallMs = 50, chunk = 512,
            clock = { t }, pause = { t += it; s.drain() },
        )
        out.write(ByteArray(4096))
        out.close()
        assertEquals(4096, s.received.size())
    }
}
