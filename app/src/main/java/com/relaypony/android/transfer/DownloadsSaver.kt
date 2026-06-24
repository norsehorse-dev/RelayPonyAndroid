package com.relaypony.android.transfer

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

/**
 * Copies a received file from app-private storage into the public Downloads collection. The path
 * forks by Android version: API 29+ uses scoped storage via MediaStore (no permission needed),
 * while API 23-28 writes directly to the public Downloads directory and requires the caller to
 * have obtained WRITE_EXTERNAL_STORAGE first.
 */
object DownloadsSaver {

    /** Returns true on success. Never throws; failures are reported as false. */
    fun save(context: Context, source: File, name: String, mime: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, source, name, mime)
        } else {
            saveLegacy(source, name)
        }
        true
    } catch (t: Throwable) {
        false
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(context: Context, source: File, name: String, mime: String) {
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, pending) ?: throw IOException("MediaStore insert failed")
        resolver.openOutputStream(uri).use { out ->
            requireNotNull(out) { "could not open output for $uri" }
            source.inputStream().use { it.copyTo(out) }
        }
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(source: File, name: String) {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloads.mkdirs()
        var dest = File(downloads, name)
        if (dest.exists()) {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var n = 1
            while (dest.exists()) { dest = File(downloads, "$base ($n)$ext"); n++ }
        }
        source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
    }
}
