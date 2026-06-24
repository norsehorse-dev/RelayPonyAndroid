package com.relaypony.android.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.relaypony.session.FileNames
import com.relaypony.session.OutgoingFile
import java.io.IOException

/**
 * Turns an Android share intent (ACTION_SEND / ACTION_SEND_MULTIPLE) into the [OutgoingFile] list
 * the session layer already knows how to send. Each file's bytes are opened lazily from its content
 * URI on the send thread; only name/size/mime are resolved up front. Names are sanitised so a
 * hostile display name can't influence where the receiver writes the file.
 */
object SharedFiles {

    fun fromIntent(context: Context, intent: Intent): List<OutgoingFile> {
        @Suppress("DEPRECATION")
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { listOf(it) } ?: emptyList()
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            else -> emptyList()
        }
        return uris.map { toOutgoing(context, it) }
    }

    /** Same conversion for content URIs chosen via the in-app file picker (SAF). */
    fun fromUris(context: Context, uris: List<Uri>): List<OutgoingFile> =
        uris.map { toOutgoing(context, it) }

    private fun toOutgoing(context: Context, uri: Uri): OutgoingFile {
        val resolver = context.contentResolver
        var displayName: String? = null
        var size = 0L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        val name = FileNames.sanitize(displayName)
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return OutgoingFile(name, mime, size) {
            resolver.openInputStream(uri) ?: throw IOException("cannot open shared file: $uri")
        }
    }
}
