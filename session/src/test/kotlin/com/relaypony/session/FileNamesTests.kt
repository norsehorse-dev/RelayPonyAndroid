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
    fun replacesDelete() {
        assertEquals("a_b", FileNames.sanitize("a\u007fb"))
    }

    @Test
    fun capsOverlongNamesKeepingExtension() {
        val out = FileNames.sanitize("a".repeat(400) + ".jpg")
        assertEquals(true, out.endsWith(".jpg"))
        assertEquals(true, out.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_NAME_BYTES)
    }

    @Test
    fun capsOverlongMultibyteNamesOnCodePointBoundary() {
        val out = FileNames.sanitize("\u00e9".repeat(300) + ".txt")
        assertEquals(true, out.endsWith(".txt"))
        assertEquals(true, out.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_NAME_BYTES)
        assertEquals(false, out.contains('\uFFFD'))
    }

    @Test
    fun traversalCorpusNeverYieldsASeparatorOrDotDot() {
        val corpus = listOf(
            "../../../../sdcard/Android/data/victim/x", "/etc/passwd", "..\\..\\boot.ini",
            "a/../../b", "\u0000../x", "....//....//x", "./.", "dir/", "C:", "nul\u0000.txt",
        )
        for (raw in corpus) {
            val out = FileNames.sanitize(raw)
            assertEquals(false, out.contains('/'), raw)
            assertEquals(false, out.contains('\\'), raw)
            assertEquals(false, out == ".." || out == ".", raw)
            assertEquals(false, out.any { it.code < 0x20 }, raw)
        }
    }

    @Test
    fun honoursCustomFallback() {
        assertEquals("download", FileNames.sanitize("", fallback = "download"))
    }
}
