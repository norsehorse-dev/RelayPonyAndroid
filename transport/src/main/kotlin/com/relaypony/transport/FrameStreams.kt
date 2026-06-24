package com.relaypony.transport

import java.io.InputStream
import java.io.OutputStream

/**
 * Bridges the crypto streaming API to the wire framing on send. Bytes written here are buffered
 * and emitted as FILE_CHUNK frames (at most [chunkSize] each). Call [finish] after the encryptor
 * is done to flush the final partial chunk; the caller frames FILE_BEGIN before and FILE_END
 * after. Bounded memory: one chunk buffer. Does not close the underlying stream.
 */
class FrameChunkOutputStream(
    private val out: OutputStream,
    private val chunkSize: Int = 64 * 1024,
) : OutputStream() {
    private val buf = ByteArray(chunkSize)
    private var n = 0

    override fun write(b: Int) {
        buf[n++] = b.toByte()
        if (n == chunkSize) flushChunk()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var rem = len
        while (rem > 0) {
            val take = minOf(rem, chunkSize - n)
            System.arraycopy(b, o, buf, n, take)
            n += take; o += take; rem -= take
            if (n == chunkSize) flushChunk()
        }
    }

    private fun flushChunk() {
        if (n == 0) return
        WireProtocol.writeFrame(out, WireProtocol.FILE_CHUNK, buf.copyOf(n))
        n = 0
    }

    /** Flush the final partial chunk. Does not emit FILE_END (the caller frames that). */
    fun finish() = flushChunk()

    override fun flush() = out.flush()
}

/**
 * Bridges the wire framing to the crypto streaming API on receive. Presents the concatenated
 * FILE_CHUNK payloads as a continuous InputStream, returning EOF at FILE_END (which it consumes,
 * leaving the underlying stream positioned at the next frame). Bounded memory: one chunk at a
 * time. Does not close the underlying stream.
 */
class FrameChunkInputStream(private val input: InputStream) : InputStream() {
    private var cur: ByteArray = ByteArray(0)
    private var pos = 0
    private var ended = false

    private fun fill(): Boolean {
        if (pos < cur.size) return true
        if (ended) return false
        val frame = WireProtocol.readFrame(input)
            ?: throw WireProtocol.WireException("EOF inside file body")
        return when (frame.type) {
            WireProtocol.FILE_CHUNK -> {
                cur = frame.payload; pos = 0
                if (cur.isEmpty()) fill() else true
            }
            WireProtocol.FILE_END -> { ended = true; false }
            else -> throw WireProtocol.WireException("unexpected frame type ${frame.type} in file body")
        }
    }

    override fun read(): Int {
        if (!fill()) return -1
        return cur[pos++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val take = minOf(len, cur.size - pos)
        System.arraycopy(cur, pos, b, off, take)
        pos += take
        return take
    }
}
