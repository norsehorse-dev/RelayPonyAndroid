package com.relaypony.android.transfer

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import com.relaypony.session.FileZipSource
import com.relaypony.session.FolderZip
import com.relaypony.session.SafeZip
import com.relaypony.session.TransferLimits
import com.relaypony.session.ZipRefusal
import com.relaypony.session.UnsafeArchiveException
import java.io.File
import java.io.IOException
import java.util.Locale

/** A folder picked with the system folder picker, walked into the items FolderZip writes. */
class PickedFolder(val name: String, val items: List<FolderZip.Item>) {
    val totalBytes: Long get() = items.sumOf { it.size }
    val fileCount: Int get() = items.count { !it.isDir }

    class TooManyItemsException : Exception("folder has too many items")

    companion object {
        /** Walks a tree URI from OpenDocumentTree. Throws [TooManyItemsException] past [maxItems]. */
        fun walk(context: Context, treeUri: Uri, maxItems: Int): PickedFolder {
            val resolver = context.contentResolver
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootName = displayName(context, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId))
                ?: rootId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "Folder" }
            val items = ArrayList<FolderZip.Item>()

            fun visit(parentId: String, prefix: String, depth: Int) {
                if (depth > SafeZip.MAX_DEPTH) throw IOException("folder is nested too deeply")
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                )
                var any = false
                resolver.query(children, columns, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        any = true
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val path = if (prefix.isEmpty()) name else "$prefix/$name"
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            visit(id, path, depth + 1)
                        } else {
                            val size = if (c.isNull(3)) 0L else c.getLong(3)
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            items.add(FolderZip.Item(path, size) {
                                resolver.openInputStream(uri) ?: throw IOException("cannot open $name")
                            })
                        }
                        // The receiver counts every entry, the root and empty folders included.
                        if (items.size + 1 > maxItems) throw TooManyItemsException()
                    }
                } ?: throw IOException("cannot list folder")
                if (!any && prefix.isNotEmpty()) {
                    items.add(FolderZip.Item(prefix, 0, isDir = true) { throw IOException("directory") })
                }
            }
            visit(rootId, "", 1)
            return PickedFolder(rootName, items)
        }

        private fun displayName(context: Context, uri: Uri): String? = runCatching {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}

/**
 * Extracts a received zip (PROTOCOL_v3 section 12) into Download/RelayPony/<folder>. SafeZip
 * checks the whole archive before anything is written; this class only decides where each
 * planned path lands, and removes everything it wrote if any file fails.
 */
object FolderExtractor {
    const val PARENT = "RelayPony"

    data class Result(val folder: String, val files: Int, val skipped: Int)

    class EmptyArchiveException : Exception("nothing to extract")

    fun isArchive(name: String): Boolean = name.lowercase(Locale.ROOT).endsWith(".zip")

    fun extract(context: Context, zip: File, archiveName: String): Result {
        FileZipSource(zip).use { source ->
            val limits = TransferLimits(freeBytes = { freeBytes(context) })
            val plan = SafeZip.plan(source, archiveName, limits)
            if (plan.files.isEmpty()) throw EmptyArchiveException()
            val folder = freeFolderName(context, plan.root)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                extractToMediaStore(context, source, plan, folder)
            } else {
                extractLegacy(context, source, plan, folder)
            }
            return Result("${Environment.DIRECTORY_DOWNLOADS}/$PARENT/$folder", plan.files.size, plan.skipped)
        }
    }

    private fun freeBytes(context: Context): Long =
        (context.getExternalFilesDir(null) ?: context.filesDir).usableSpace

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    @Suppress("DEPRECATION")
    private fun publicDownloads(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** "Photos", or "Photos (2)" and up when a folder by that name is already there. */
    private fun freeFolderName(context: Context, root: String): String {
        for (n in 1..999) {
            val candidate = if (n == 1) root else "$root ($n)"
            if (!folderExists(context, candidate)) return candidate
        }
        return "$root (${System.currentTimeMillis()})"
    }

    private fun folderExists(context: Context, folder: String): Boolean {
        if (File(publicDownloads(), "$PARENT/$folder").exists()) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val prefix = "${Environment.DIRECTORY_DOWNLOADS}/$PARENT/$folder/"
        val escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'",
                arrayOf("$escaped%"),
                null,
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun extractToMediaStore(context: Context, source: FileZipSource, plan: SafeZip.Plan, folder: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val written = ArrayList<Uri>()
        try {
            SafeZip.extract(source, plan) { entry ->
                val leaf = entry.path.substringAfterLast('/')
                val sub = entry.path.substringBeforeLast('/', "")
                val relative = buildString {
                    append(Environment.DIRECTORY_DOWNLOADS).append('/').append(PARENT).append('/').append(folder).append('/')
                    if (sub.isNotEmpty()) append(sub).append('/')
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, leaf)
                    put(MediaStore.Downloads.MIME_TYPE, mimeFor(leaf))
                    put(MediaStore.Downloads.RELATIVE_PATH, relative)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
                written.add(uri)
                resolver.openOutputStream(uri) ?: throw IOException("cannot open $leaf")
            }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            written.forEach { resolver.update(it, done, null, null) }
        } catch (t: Throwable) {
            written.forEach { runCatching { resolver.delete(it, null, null) } }
            throw t
        }
    }

    private fun extractLegacy(context: Context, source: FileZipSource, plan: SafeZip.Plan, folder: String) {
        val dir = File(publicDownloads(), "$PARENT/$folder")
        val base = dir.canonicalPath + File.separator
        val written = ArrayList<File>()
        try {
            SafeZip.extract(source, plan) { entry ->
                val target = File(dir, entry.path)
                // SafeZip already refuses anything that leaves the root; this is the backstop.
                if (!target.canonicalPath.startsWith(base)) {
                    throw UnsafeArchiveException(ZipRefusal.UNSAFE_PATH, "outside the folder")
                }
                target.parentFile?.mkdirs()
                written.add(target)
                target.outputStream()
            }
        } catch (t: Throwable) {
            written.forEach { runCatching { it.delete() } }
            throw t
        }
        MediaScannerConnection.scanFile(context, written.map { it.path }.toTypedArray(), null, null)
    }
}
