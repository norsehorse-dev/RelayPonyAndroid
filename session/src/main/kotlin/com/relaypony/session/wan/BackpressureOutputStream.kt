package com.relaypony.session.wan

import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream

/**
 * Feeds a reliable stream (PonyDirect direct, or the relay-forward channel) from a blocking writer
 * such as [com.relaypony.session.Session.send], so a WAN send is encrypted from disk as it goes
 * instead of being built in memory first (4.0).
 *
 * Bytes are passed on in [chunk]-sized pieces. Before each piece it waits until the stream holds
 * fewer than [highWater] unacknowledged bytes, so memory stays near [highWater] whatever the file
 * size. If the stream stops draining for [stallMs] the write fails (the peer went away), and
 * [cancelled] ends the wait at once when the transfer is abandoned.
 */
class BackpressureOutputStream(
    private val sink: (ByteArray) -> Unit,
    private val buffered: () -> Long,
    private val cancelled: () -> Boolean = { false },
    private val highWater: Long = HIGH_WATER_BYTES,
    private val stallMs: Long = STALL_MS,
    private val chunk: Int = CHUNK_BYTES,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) : OutputStream() {

    companion object {
        /** Unacknowledged bytes allowed in the stream before the writer waits. */
        const val HIGH_WATER_BYTES: Long = 8L shl 20
        /** No acknowledgement progress for this long fails the send. */
        const val STALL_MS: Long = 60_000L
        const val CHUNK_BYTES: Int = 64 * 1024
        private const val POLL_MS = 10L
    }

    private val buf = ByteArray(chunk)
    private var n = 0
    private var closed = false

    /** Total bytes handed to [sink]. */
    var written: Long = 0
        private set

    override fun write(b: Int) {
        buf[n++] = b.toByte()
        if (n == chunk) push()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var rem = len
        while (rem > 0) {
            val take = minOf(rem, chunk - n)
            System.arraycopy(b, o, buf, n, take)
            n += take; o += take; rem -= take
            if (n == chunk) push()
        }
    }

    override fun flush() { if (n > 0) push() }

    override fun close() {
        if (closed) return
        flush()
        closed = true
    }

    private fun push() {
        waitForRoom()
        sink(buf.copyOf(n))
        written += n
        n = 0
    }

    private fun waitForRoom() {
        var lowest = buffered()
        var since = clock()
        while (true) {
            if (cancelled()) throw InterruptedIOException("send cancelled")
            val held = buffered()
            if (held < highWater) return
            if (held < lowest) { lowest = held; since = clock() }
            else if (clock() - since >= stallMs) throw IOException("the other device stopped acknowledging")
            try { pause(POLL_MS) } catch (e: InterruptedException) { throw InterruptedIOException("send interrupted") }
        }
    }
}
