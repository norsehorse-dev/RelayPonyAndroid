package com.relaypony.session

/**
 * Filename hygiene for transferred files. The manifest carries a filename the sender chose, and
 * the receiver writes a file by that name; without sanitisation a hostile or malformed name like
 * "../../secret" or "/etc/passwd" could escape the intended directory on the receiving device.
 * [sanitize] reduces any input to a single safe path segment.
 */
object FileNames {
    // Path separators and control characters are never allowed in a single filename segment.
    private val UNSAFE = Regex("[\\\\/\\u0000-\\u001f]")

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
        return cleaned.ifEmpty { fallback }
    }
}
