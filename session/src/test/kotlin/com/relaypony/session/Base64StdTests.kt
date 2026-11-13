package com.relaypony.session

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.random.Random

class Base64StdTests {

    @Test
    fun matchesJdkBase64ForEveryLengthUpTo300() {
        val rng = Random(7)
        for (len in 0..300) {
            val bytes = rng.nextBytes(len)
            val jdk = Base64.getEncoder().encodeToString(bytes)
            assertEquals(jdk, Base64Std.encode(bytes), "encode, length $len")
            assertArrayEquals(bytes, Base64Std.decode(jdk), "decode, length $len")
        }
    }

    @Test
    fun rfc4648Vectors() {
        val cases = listOf("" to "", "f" to "Zg==", "fo" to "Zm8=", "foo" to "Zm9v", "foob" to "Zm9vYg==", "fooba" to "Zm9vYmE=", "foobar" to "Zm9vYmFy")
        for ((plain, enc) in cases) {
            assertEquals(enc, Base64Std.encode(plain.toByteArray()))
            assertEquals(plain, String(Base64Std.decode(enc)))
        }
    }

    @Test
    fun decodeSkipsWhitespaceAndAcceptsMissingPadding() {
        assertEquals("foob", String(Base64Std.decode("Zm9v\nYg")))
        assertEquals("fo", String(Base64Std.decode(" Zm8 ")))
    }

    @Test
    fun decodeRejectsGarbage() {
        assertThrows(IllegalArgumentException::class.java) { Base64Std.decode("Zm9v!") }
        assertThrows(IllegalArgumentException::class.java) { Base64Std.decode("Zm=9v") }
        assertThrows(IllegalArgumentException::class.java) { Base64Std.decode("Z") }
    }
}
