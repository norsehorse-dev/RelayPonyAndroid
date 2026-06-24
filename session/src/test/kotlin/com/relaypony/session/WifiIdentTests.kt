package com.relaypony.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WifiIdentTests {

    @Test
    fun roundTrips() {
        val ident = Ident(schemeId = 1, handle = "age1qqqqexamplehandle", deviceName = "Pixel 7", wantsToSend = true)
        val bytes = ByteArrayOutputStream().also { WifiIdent.writeTo(it, ident) }.toByteArray()
        val back = WifiIdent.readFrom(ByteArrayInputStream(bytes))
        assertEquals(ident, back)
    }

    @Test
    fun roundTripsUnicodeDeviceName() {
        val ident = Ident(2, "age1zzz", "Kévin's \u00fcber-phone \u2603", false)
        val bytes = ByteArrayOutputStream().also { WifiIdent.writeTo(it, ident) }.toByteArray()
        assertEquals(ident, WifiIdent.readFrom(ByteArrayInputStream(bytes)))
    }

    @Test
    fun resolvesSenderAndReceiver() {
        val sender = Ident(1, "a", "A", wantsToSend = true)
        val receiver = Ident(1, "b", "B", wantsToSend = false)
        assertTrue(WifiIdent.resolveISend(sender, receiver))
        assertFalse(WifiIdent.resolveISend(receiver, sender))
    }

    @Test
    fun bothWantingToSendIsAnError() {
        val a = Ident(1, "a", "A", wantsToSend = true)
        val b = Ident(1, "b", "B", wantsToSend = true)
        val ex = assertThrows(IllegalStateException::class.java) { WifiIdent.resolveISend(a, b) }
        assertTrue(ex.message!!.contains("both"))
    }

    @Test
    fun neitherWantingToSendIsAnError() {
        val a = Ident(1, "a", "A", wantsToSend = false)
        val b = Ident(1, "b", "B", wantsToSend = false)
        val ex = assertThrows(IllegalStateException::class.java) { WifiIdent.resolveISend(a, b) }
        assertTrue(ex.message!!.contains("neither"))
    }

    @Test
    fun rejectsUnknownVersion() {
        val bytes = byteArrayOf(99, 1, 1, 0, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) {
            WifiIdent.readFrom(ByteArrayInputStream(bytes))
        }
    }
}
