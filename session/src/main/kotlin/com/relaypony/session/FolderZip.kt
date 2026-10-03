package com.relaypony.session

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The sending side of a folder (PROTOCOL_v3 section 12.1): one zip whose single top-level
 * directory is the folder itself. Deflate at level 0, because photos and video, the usual
 * contents, do not shrink, and the CPU is better spent on the transfer.
 */
object FolderZip {
    /** One file or (empty) directory, [path] relative to the folder, '/'-separated. */
    class Item(val path: String, val size: Long, val isDir: Boolean = false, val open: () -> InputStream)

    class CancelledException : Exception("folder zip cancelled")

    /** The archive's file name for a folder called [folderName]. */
    fun archiveName(folderName: String): String = FileNames.sanitize(folderName, fallback = "Folder") + ".zip"

    fun write(
        out: OutputStream,
        folderName: String,
        items: List<Item>,
        cancelled: () -> Boolean = { false },
        onBytes: (Long) -> Unit = {},
    ) {
        val root = FileNames.sanitize(folderName, fallback = "Folder")
        val zip = ZipOutputStream(out)
        zip.setLevel(Deflater.NO_COMPRESSION)
        zip.putNextEntry(ZipEntry("$root/"))
        zip.closeEntry()
        val buf = ByteArray(64 * 1024)
        for (item in items) {
            if (cancelled()) throw CancelledException()
            val clean = item.path.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
                .joinToString("/") { FileNames.sanitize(it, fallback = "_") }
            if (clean.isEmpty()) continue
            if (item.isDir) {
                zip.putNextEntry(ZipEntry("$root/$clean/"))
                zip.closeEntry()
                continue
            }
            zip.putNextEntry(ZipEntry("$root/$clean"))
            item.open().use { input ->
                while (true) {
                    if (cancelled()) throw CancelledException()
                    val n = input.read(buf)
                    if (n < 0) break
                    zip.write(buf, 0, n)
                    onBytes(n.toLong())
                }
            }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }
}
