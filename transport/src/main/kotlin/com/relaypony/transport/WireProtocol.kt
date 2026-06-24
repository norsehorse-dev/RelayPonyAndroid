package com.relaypony.transport

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The RelayPony wire protocol: length-prefixed, scheme-tagged frames. Cipher-agnostic — every
 * payload is opaque bytes the transport never interprets (architecture spec sections 2 and 6).
 *
 * Frame layout: [1 byte type][4 byte big-endian uint32 length][length bytes payload].
 *
 * Session order (one direction, sender -> receiver):
 *   HELLO, MANIFEST, (FILE_BEGIN, FILE_CHUNK*, FILE_END) per file, DONE.
 * ACK is reserved for the bidirectional flow added with the real socket transport in Phase 3.
 *
 * HELLO is the only structured frame and uses an explicit binary layout (no JSON), so the
 * transport needs no serialization dependency:
 *   [1 byte wireVersion][1 byte schemeId][2 byte u16 nameLen][name UTF-8][2 byte u16 handleLen][handle UTF-8]
 *
 * THIS FORMAT IS FROZEN as of Phase 2. Any change must bump [WIRE_VERSION] and be treated as
 * breaking, including across the future PGPony reuse.
 */
object WireProtocol {
    const val WIRE_VERSION: Int = 1

    const val HELLO: Byte = 0x01
    const val MANIFEST: Byte = 0x02
    const val FILE_BEGIN: Byte = 0x03
    const val FILE_CHUNK: Byte = 0x04
    const val FILE_END: Byte = 0x05
    const val ACK: Byte = 0x06       // reserved for the bidirectional flow in Phase 3
    const val DONE: Byte = 0x07

    class WireException(message: String) : Exception(message)

    class Frame(val type: Byte, val payload: ByteArray)
    class Hello(
        val version: Int,
        val schemeId: Byte,
        val deviceName: String,
        val recipientHandle: String,
    )

    fun writeFrame(out: OutputStream, type: Byte, payload: ByteArray) {
        out.write(type.toInt() and 0xff)
        writeU32(out, payload.size)
        out.write(payload)
    }

    /** Read a frame, or null at clean EOF (nothing left to read). */
    fun readFrame(input: InputStream): Frame? {
        val t = input.read()
        if (t < 0) return null
        val len = readU32(input)
        val payload = readFully(input, len)
        if (payload.size != len) throw WireException("truncated frame: wanted $len, got ${payload.size}")
        return Frame(t.toByte(), payload)
    }

    fun writeHello(out: OutputStream, schemeId: Byte, deviceName: String, recipientHandle: String) {
        val name = deviceName.toByteArray(Charsets.UTF_8)
        val handle = recipientHandle.toByteArray(Charsets.UTF_8)
        val body = ByteArrayOutputStream()
        body.write(WIRE_VERSION and 0xff)
        body.write(schemeId.toInt() and 0xff)
        writeU16(body, name.size)
        body.write(name)
        writeU16(body, handle.size)
        body.write(handle)
        writeFrame(out, HELLO, body.toByteArray())
    }

    fun readHello(input: InputStream): Hello {
        val frame = readFrame(input) ?: throw WireException("expected HELLO, got EOF")
        if (frame.type != HELLO) throw WireException("expected HELLO frame, got type ${frame.type}")
        val p = frame.payload
        var i = 0
        fun u8(): Int {
            if (i >= p.size) throw WireException("HELLO truncated")
            return p[i++].toInt() and 0xff
        }
        fun u16(): Int = (u8() shl 8) or u8()
        fun take(n: Int): ByteArray {
            if (i + n > p.size) throw WireException("HELLO truncated")
            val b = p.copyOfRange(i, i + n); i += n; return b
        }
        val version = u8()
        val scheme = u8().toByte()
        val name = String(take(u16()), Charsets.UTF_8)
        val handle = String(take(u16()), Charsets.UTF_8)
        return Hello(version, scheme, name, handle)
    }

    // --- byte helpers ---

    internal fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    private fun writeU32(out: OutputStream, v: Int) {
        out.write((v ushr 24) and 0xff)
        out.write((v ushr 16) and 0xff)
        out.write((v ushr 8) and 0xff)
        out.write(v and 0xff)
    }

    private fun readU32(input: InputStream): Int {
        val b = readFully(input, 4)
        if (b.size != 4) throw WireException("EOF reading frame length")
        return ((b[0].toInt() and 0xff) shl 24) or
            ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or
            (b[3].toInt() and 0xff)
    }

    private fun writeU16(out: OutputStream, v: Int) {
        out.write((v ushr 8) and 0xff)
        out.write(v and 0xff)
    }
}
