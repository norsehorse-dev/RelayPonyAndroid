package com.relaypony.session

import com.relaypony.session.pairing.InboxIds
import com.relaypony.session.pairing.PeerRoute
import com.relaypony.session.pairing.PinnedDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** 4.0: a backup carries this device's inbox and each paired device's route, and still reads without them. */
class IdentityBackupRoutesTests {
    private val secret = "AGE-SECRET-KEY-1QYQSZQGPQYQSZQGPQYQSZQGPQYQSZQGPQYQSZQGPQYQSZQGPQYQS3290GG"
    private val handle = "age1q73he0q5yzfu3d64msd3p6rvksnrwjk3d2598mgtmlqt9wrdr37q2vrn72"

    @Test
    fun inboxAndRoutesSurviveARoundTrip() {
        val inbox = InboxIds.generate()
        val route = PeerRoute(InboxIds.generate(), "https://relay.example.com")
        val out = ByteArrayOutputStream()
        IdentityBackup.export("pw", secret, listOf(PinnedDevice(handle, "Tablet", 1L)), out,
            inboxId = inbox, routes = mapOf(handle to route))
        val back = IdentityBackup.import("pw", ByteArrayInputStream(out.toByteArray()))
        assertEquals(inbox, back.inboxId)
        assertEquals(route, back.routes[handle])
        assertEquals("Tablet", back.devices.single().name)
    }

    @Test
    fun aBackupWithoutThemStillImports() {
        val out = ByteArrayOutputStream()
        IdentityBackup.export("pw", secret, listOf(PinnedDevice(handle, "Tablet", 1L)), out)
        val back = IdentityBackup.import("pw", ByteArrayInputStream(out.toByteArray()))
        assertNull(back.inboxId)
        assertEquals(emptyMap<String, PeerRoute>(), back.routes)
    }
}
