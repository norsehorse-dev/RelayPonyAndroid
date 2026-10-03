package com.relaypony.session

/**
 * Filename hygiene for transferred files. The manifest carries a filename the sender chose, and
 * the receiver writes a file by that name; without sanitisation a hostile or malformed name like
 * "../../secret" or "/etc/passwd" could escape the intended directory on the receiving device.
 * [sanitize] reduces any input to a single safe path segment.
 */
object FileNames {
    // Path separators and control characters are never allowed in a single filename segment.
    private val UNSAFE = Regex("[\\\\/\\u0000-\\u001f\\u007f]")

    fun sanitize(displayName: String?, fallback: String = "file.bin"): String {
        // Keep only the last path segment, so any directory components are dropped.
        val lastSegment = displayName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            .orEmpty()
        val cleaned = lastSegment
            .replace(UNSAFE, "_")
            .trim()
            .trim('.')          // a name of only dots (".", "..") collapses to empty
        return capLength(cleaned.ifEmpty { fallback })
    }

    /** Most filesystems cap a name at 255 bytes; stay well under it, in UTF-8 bytes. */
    const val MAX_NAME_BYTES: Int = 200

    /** Shortens an over-long name to [MAX_NAME_BYTES] UTF-8 bytes, keeping a short extension. */
    private fun capLength(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        val budget = MAX_NAME_BYTES - ext.toByteArray(Charsets.UTF_8).size
        val base = StringBuilder()
        var used = 0
        var i = 0
        val stem = name.substring(0, name.length - ext.length)
        while (i < stem.length) {
            val cp = stem.codePointAt(i)
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + bytes > budget) break
            base.appendCodePoint(cp)
            used += bytes
            i += Character.charCount(cp)
        }
        return base.toString().trimEnd().ifEmpty { "file" } + ext
    }
}
