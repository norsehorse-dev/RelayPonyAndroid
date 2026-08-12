package com.relaypony.android.transfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.util.LruCache

/**
 * A6: a tiny in-memory thumbnail cache and downsampled decode for the inbox image rows. Dependency
 * free — framework BitmapFactory plus the framework ExifInterface (API 24+, guarded) — so no new
 * Gradle libraries. Decoding is meant to run off the main thread (see InboxScreen).
 */
object Thumbnails {

    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** A previously decoded thumbnail, if still cached — lets the row paint instantly on scroll. */
    fun cached(path: String): Bitmap? = cache.get(path)

    /** Decode (and cache) a thumbnail no larger than [maxPx] on its long edge, or null on failure. */
    fun decode(path: String, maxPx: Int): Bitmap? {
        cache.get(path)?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val larger = maxOf(bounds.outWidth, bounds.outHeight)
            while (larger / sample > maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeFile(path, opts) ?: return null
            val rotated = applyExifRotation(path, decoded)
            cache.put(path, rotated)
            rotated
        }.getOrNull()
    }

    /** Rotate to match EXIF orientation so portrait photos aren't shown sideways. ExifInterface is
     *  API 24+, so on older devices the thumbnail is simply left unrotated. */
    private fun applyExifRotation(path: String, bmp: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return bmp
        return runCatching {
            val orientation = ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) {
                bmp
            } else {
                val matrix = Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            }
        }.getOrDefault(bmp)
    }
}
