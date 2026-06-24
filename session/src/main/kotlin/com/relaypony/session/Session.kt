package com.relaypony.session

import com.relaypony.crypto.CryptoProvider
import com.relaypony.crypto.Identity
import com.relaypony.crypto.Recipient
import com.relaypony.transport.FrameChunkInputStream
import com.relaypony.transport.FrameChunkOutputStream
import com.relaypony.transport.WireProtocol
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Thrown when the receiver's provider can't speak the scheme the sender advertised in HELLO. */
class UnsupportedSchemeException(expected: Byte, got: Byte) : Exception(
    "unsupported scheme: expected 0x%02x, got 0x%02x".format(expected, got)
)

/** Thrown when frames arrive out of the expected order. */
class ProtocolException(message: String) : Exception(message)

/** A file to send: metadata plus a factory that opens its plaintext input stream on demand. */
class OutgoingFile(
    val name: String,
    val mime: String,
    val size: Long,
    val open: () -> InputStream,
)

/** Chooses where each received file's decrypted bytes are written. */
fun interface FileSink {
    fun openSink(entry: FileEntry): OutputStream
}

/**
 * Session orchestration: ties a [CryptoProvider] to the [WireProtocol] framing. Cipher-agnostic by
 * construction — it never names age; swap the provider and the same flow carries OpenPGP (the
 * PGPony reuse). Phase 2 runs one-directional (sender -> receiver, ending with DONE) over any
 * stream pair, including an in-memory loopback. Bidirectional ACKs arrive with the socket
 * transport in Phase 3.
 */
/** What a completed receive yields: the file manifest plus who sent it (from the HELLO frame). */
data class ReceiveResult(
    val manifest: Manifest,
    val senderName: String,
    val senderHandle: String,
)

object Session {
    private val json = Json { ignoreUnknownKeys = true }

    fun send(
        provider: CryptoProvider,
        recipients: List<Recipient>,
        deviceName: String,
        senderRecipientHandle: String,
        files: List<OutgoingFile>,
        out: OutputStream,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        WireProtocol.writeHello(out, provider.schemeId, deviceName, senderRecipientHandle)

        val totalBytes = files.sumOf { it.size }
        var sentBytes = 0L
        var lastSentPct = -1L
        onProgress?.invoke(0, totalBytes)

        val manifest = Manifest(files.map { FileEntry(it.name, it.size, it.mime) })
        val manifestJson = json.encodeToString(Manifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
        val encryptedManifest = ByteArrayOutputStream()
        provider.encryptStream(recipients, ByteArrayInputStream(manifestJson), encryptedManifest)
        WireProtocol.writeFrame(out, WireProtocol.MANIFEST, encryptedManifest.toByteArray())

        for (file in files) {
            WireProtocol.writeFrame(out, WireProtocol.FILE_BEGIN, EMPTY)
            val chunked = FrameChunkOutputStream(out)
            file.open().use { raw ->
                val counting = CountingInputStream(raw) { delta ->
                    sentBytes += delta
                    val pct = if (totalBytes > 0) sentBytes * 100 / totalBytes else 100
                    if (pct != lastSentPct) {
                        lastSentPct = pct
                        onProgress?.invoke(sentBytes, totalBytes)
                    }
                }
                provider.encryptStream(recipients, counting, chunked)
            }
            chunked.finish()
            WireProtocol.writeFrame(out, WireProtocol.FILE_END, EMPTY)
        }

        onProgress?.invoke(totalBytes, totalBytes)
        WireProtocol.writeFrame(out, WireProtocol.DONE, EMPTY)
        out.flush()
    }

    /** Reads a full session, writing each file's plaintext to the sink the caller provides.
     *  Returns the decrypted manifest plus the sender's advertised name and handle. */
    fun receive(
        provider: CryptoProvider,
        identity: Identity,
        input: InputStream,
        sink: FileSink,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): ReceiveResult {
        val hello = WireProtocol.readHello(input)
        if (hello.schemeId != provider.schemeId) {
            throw UnsupportedSchemeException(provider.schemeId, hello.schemeId)
        }

        val manifestFrame = WireProtocol.readFrame(input)
            ?: throw ProtocolException("expected MANIFEST, got EOF")
        if (manifestFrame.type != WireProtocol.MANIFEST) {
            throw ProtocolException("expected MANIFEST frame, got type ${manifestFrame.type}")
        }
        val manifestPlain = ByteArrayOutputStream()
        provider.decryptStream(identity, ByteArrayInputStream(manifestFrame.payload), manifestPlain)
        val manifest = json.decodeFromString(
            Manifest.serializer(),
            String(manifestPlain.toByteArray(), Charsets.UTF_8),
        )

        val totalBytes = manifest.files.sumOf { it.size }
        var recvBytes = 0L
        var lastRecvPct = -1L
        onProgress?.invoke(0, totalBytes)

        for (entry in manifest.files) {
            val begin = WireProtocol.readFrame(input)
                ?: throw ProtocolException("expected FILE_BEGIN, got EOF")
            if (begin.type != WireProtocol.FILE_BEGIN) {
                throw ProtocolException("expected FILE_BEGIN frame, got type ${begin.type}")
            }
            val body = FrameChunkInputStream(input)
            sink.openSink(entry).use { rawOut ->
                val counting = CountingOutputStream(rawOut) { delta ->
                    recvBytes += delta
                    val pct = if (totalBytes > 0) recvBytes * 100 / totalBytes else 100
                    if (pct != lastRecvPct) {
                        lastRecvPct = pct
                        onProgress?.invoke(recvBytes, totalBytes)
                    }
                }
                provider.decryptStream(identity, body, counting)
            }
        }

        onProgress?.invoke(totalBytes, totalBytes)
        val done = WireProtocol.readFrame(input)
            ?: throw ProtocolException("expected DONE, got EOF")
        if (done.type != WireProtocol.DONE) {
            throw ProtocolException("expected DONE frame, got type ${done.type}")
        }
        return ReceiveResult(manifest, hello.deviceName, hello.recipientHandle)
    }

    private val EMPTY = ByteArray(0)
}

private class CountingInputStream(
    private val wrapped: InputStream,
    private val onRead: (Long) -> Unit,
) : InputStream() {
    override fun read(): Int {
        val b = wrapped.read()
        if (b >= 0) onRead(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = wrapped.read(b, off, len)
        if (n > 0) onRead(n.toLong())
        return n
    }

    override fun close() = wrapped.close()
}

private class CountingOutputStream(
    private val wrapped: OutputStream,
    private val onWrite: (Long) -> Unit,
) : OutputStream() {
    override fun write(b: Int) {
        wrapped.write(b)
        onWrite(1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        wrapped.write(b, off, len)
        onWrite(len.toLong())
    }

    override fun flush() = wrapped.flush()
    override fun close() = wrapped.close()
}
