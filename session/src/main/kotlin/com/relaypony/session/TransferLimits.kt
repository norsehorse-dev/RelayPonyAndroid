package com.relaypony.session

import com.relaypony.transport.WireProtocol
import java.io.OutputStream

/**
 * Receive-side ceilings (audit 2.5). Enforced on the plaintext bytes actually written, never on
 * the sizes the sender declares in its manifest, because the manifest is sender-controlled. The
 * declared total is only used for a cheap early reject against free space.
 *
 * [freeBytes] reports usable space where received files land (File.usableSpace in :app). When it
 * is null the free-space checks are skipped, which keeps :session free of Android APIs and keeps
 * in-memory tests simple.
 */
data class TransferLimits(
    val maxFileBytes: Long = MAX_FILE_BYTES,
    val maxTransferBytes: Long = MAX_TRANSFER_BYTES,
    val maxEntries: Int = WireProtocol.MAX_MANIFEST_ENTRIES,
    val freeSpaceMarginBytes: Long = FREE_SPACE_MARGIN_BYTES,
    val freeBytes: (() -> Long)? = null,
) {
    companion object {
        const val MAX_FILE_BYTES: Long = 16L shl 30
        const val MAX_TRANSFER_BYTES: Long = 64L shl 30
        const val FREE_SPACE_MARGIN_BYTES: Long = 500L shl 20

        /** How often, in written bytes, the free-space margin is re-checked during a receive. */
        const val FREE_SPACE_RECHECK_BYTES: Long = 8L shl 20

        val DEFAULT = TransferLimits()
    }

    /** Rejects a decrypted manifest before any file body is read. */
    fun checkManifest(manifest: Manifest) {
        val files = manifest.files
        if (files.size > maxEntries) {
            throw TransferTooLargeException("transfer lists ${files.size} files (limit $maxEntries)")
        }
        var declared = 0L
        for (f in files) {
            if (f.size < 0) throw ProtocolException("negative file size in manifest")
            if (f.size > maxFileBytes) {
                throw TransferTooLargeException("file is ${f.size} bytes (limit $maxFileBytes)")
            }
            declared += f.size
            if (declared > maxTransferBytes) {
                throw TransferTooLargeException("transfer exceeds $maxTransferBytes bytes")
            }
        }
        val free = freeBytes?.invoke() ?: return
        if (declared + freeSpaceMarginBytes > free) {
            throw InsufficientSpaceException(declared, free)
        }
    }
}

/** A receive crossed a [TransferLimits] ceiling. The partial file is the caller's to delete. */
class TransferTooLargeException(message: String) : Exception(message)

/** Not enough free space to take the transfer while keeping the safety margin. */
class InsufficientSpaceException(val needed: Long, val available: Long) :
    Exception("not enough free space: need $needed bytes plus margin, $available available")

/**
 * Counts plaintext written for one file and throws as soon as the per-file or per-transfer
 * ceiling is crossed, or the free-space margin is reached. The bytes that crossed the line are
 * not passed through.
 */
internal class LimitingOutputStream(
    private val wrapped: OutputStream,
    private val limits: TransferLimits,
    private val transferSoFar: () -> Long,
    private val onWritten: (Long) -> Unit,
) : OutputStream() {
    private var written = 0L
    private var sinceSpaceCheck = 0L

    private fun admit(n: Long) {
        if (written + n > limits.maxFileBytes) {
            throw TransferTooLargeException("file exceeds ${limits.maxFileBytes} bytes")
        }
        if (transferSoFar() + n > limits.maxTransferBytes) {
            throw TransferTooLargeException("transfer exceeds ${limits.maxTransferBytes} bytes")
        }
        val free = limits.freeBytes
        if (free != null) {
            sinceSpaceCheck += n
            if (sinceSpaceCheck >= TransferLimits.FREE_SPACE_RECHECK_BYTES) {
                sinceSpaceCheck = 0
                val available = free()
                if (available - n < limits.freeSpaceMarginBytes) {
                    throw InsufficientSpaceException(n, available)
                }
            }
        }
    }

    override fun write(b: Int) {
        admit(1)
        wrapped.write(b)
        written += 1
        onWritten(1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        admit(len.toLong())
        wrapped.write(b, off, len)
        written += len
        onWritten(len.toLong())
    }

    override fun flush() = wrapped.flush()
    override fun close() = wrapped.close()
}
