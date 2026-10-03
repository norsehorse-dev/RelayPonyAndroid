package com.relaypony.session.wan

import com.relaypony.session.InsufficientSpaceException
import com.relaypony.session.TransferLimits
import com.relaypony.session.TransferTooLargeException
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Disk spool for one inbound WAN transfer. Replaces the in-memory buffer 3.0 used, so a WAN
 * receive no longer needs the whole serialized session in RAM (4.0 P0, audit 2.5 on the WAN path).
 *
 * The spool holds the serialized session exactly as the sender produced it (HELLO, age-encrypted
 * MANIFEST and bodies), so it is ciphertext at rest. [Session.receive] then decodes it from disk
 * with the usual [TransferLimits], and the caller deletes the spool afterwards.
 *
 * The cap is on spooled bytes: the transfer ceiling plus framing and age STREAM overhead. Free
 * space is re-checked every [TransferLimits.FREE_SPACE_RECHECK_BYTES]. Not thread-safe: each
 * spool is written from one thread (PonyDirect's exec thread, or the relay channel's loop).
 */
class WanSpool(
    val file: File,
    private val limits: TransferLimits,
) {
    private val out = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
    private val maxBytes: Long = maxSpoolBytes(limits.maxTransferBytes)
    private var sinceSpaceCheck = 0L

    var size: Long = 0
        private set

    /** Appends [bytes]. Throws [TransferTooLargeException] or [InsufficientSpaceException] when a
     *  ceiling is crossed; the caller then calls [discard]. */
    fun write(bytes: ByteArray) {
        val n = bytes.size.toLong()
        if (size + n > maxBytes) throw TransferTooLargeException("internet transfer exceeds $maxBytes bytes")
        val free = limits.freeBytes
        if (free != null) {
            sinceSpaceCheck += n
            if (sinceSpaceCheck >= TransferLimits.FREE_SPACE_RECHECK_BYTES) {
                sinceSpaceCheck = 0
                val available = free()
                if (available - n < limits.freeSpaceMarginBytes) throw InsufficientSpaceException(n, available)
            }
        }
        out.write(bytes)
        size += n
    }

    /** Closes the spool and returns its file, ready to decode. */
    fun finish(): File {
        out.flush()
        out.close()
        return file
    }

    /** Closes and deletes the spool. Safe to call more than once. */
    fun discard() {
        runCatching { out.close() }
        runCatching { file.delete() }
    }

    companion object {
        /** Ciphertext and framing overhead allowance: age STREAM adds 16 bytes per 64 KiB chunk,
         *  framing 5 bytes per frame, plus headers and the manifest. 1/1024 + 8 MiB covers it. */
        fun maxSpoolBytes(maxTransferBytes: Long): Long = maxTransferBytes + maxTransferBytes / 1024 + (8L shl 20)

        /** Creates a fresh spool file for [peer] in [dir]. The name carries no handle. */
        fun create(dir: File, limits: TransferLimits): WanSpool {
            dir.mkdirs()
            return WanSpool(File.createTempFile("rx-", ".spool", dir), limits)
        }

        /** Deletes spools left over from a crash or a killed process. Call once at startup. */
        fun sweep(dir: File) {
            dir.listFiles { f -> f.name.endsWith(".spool") }?.forEach { runCatching { it.delete() } }
        }
    }
}
