package com.relaypony.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FileNamesTests {

    @Test
    fun keepsOrdinaryNames() {
        assertEquals("photo.jpg", FileNames.sanitize("photo.jpg"))
        assertEquals("My Report (v2).pdf", FileNames.sanitize("My Report (v2).pdf"))
    }

    @Test
    fun stripsDirectoryTraversal() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("x", FileNames.sanitize("C:\\Windows\\x"))
        assertEquals("c.txt", FileNames.sanitize("a/b/c.txt"))
    }

    @Test
    fun collapsesDotOnlyAndEmptyToFallback() {
        assertEquals("file.bin", FileNames.sanitize(".."))
        assertEquals("file.bin", FileNames.sanitize("."))
        assertEquals("file.bin", FileNames.sanitize(""))
        assertEquals("file.bin", FileNames.sanitize(null))
        assertEquals("file.bin", FileNames.sanitize("   "))
    }

    @Test
    fun replacesControlCharacters() {
        assertEquals("a_b", FileNames.sanitize("a\u0001b"))
    }

    @Test
    fun honoursCustomFallback() {
        assertEquals("download", FileNames.sanitize("", fallback = "download"))
    }
}
