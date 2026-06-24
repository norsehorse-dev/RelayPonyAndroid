package com.relaypony.session.pairing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingCoreTests {

    @Test
    fun qrPayload_roundTrips_includingAwkwardName() {
        val original = QrPayload(
            version = QrPayload.CURRENT_VERSION,
            schemeId = 0x01,
            recipientHandle = "age1qqqexamplehandle0000",
            deviceName = "Kevin's Phone | \u00e9\u00f1 #2",
        )
        val decoded = QrPayload.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun qrPayload_rejectsMalformed() {
        assertThrows(IllegalArgumentException::class.java) { QrPayload.decode("not-a-payload") }
        assertThrows(IllegalArgumentException::class.java) { QrPayload.decode("RP1|01|age1xx") }
        assertThrows(IllegalArgumentException::class.java) { QrPayload.decode("XX1|01|age1xx|name") }
    }

    @Test
    fun trustStore_pinAndLookup() {
        val store = InMemoryTrustStore()
        assertFalse(store.isPinned("age1aaa"))
        store.pin("age1aaa", "Tablet", nowMs = 1000L)
        assertTrue(store.isPinned("age1aaa"))
        assertEquals("Tablet", store.get("age1aaa")?.name)
        assertEquals(1, store.all().size)
        store.remove("age1aaa")
        assertFalse(store.isPinned("age1aaa"))
    }

    @Test
    fun pinnedPeer_isOneTapSendable_unknownIsNot() {
        val store = InMemoryTrustStore()
        val payload = QrPayload(1, 0x01, "age1real", "Kevin's Phone")
        Pairing.pinScanned(payload, store)

        assertEquals(PeerTrust.PINNED, Pairing.classify("age1real", store))
        assertTrue(Pairing.canSendOneTap("age1real", store))

        assertEquals(PeerTrust.UNKNOWN, Pairing.classify("age1never", store))
        assertFalse(Pairing.canSendOneTap("age1never", store))
    }

    @Test
    fun spoofedName_withDifferentHandle_isNotTrusted() {
        // Pin the real device by its handle.
        val store = InMemoryTrustStore()
        Pairing.pinScanned(QrPayload(1, 0x01, "age1real", "Kevin's Phone"), store)

        // Attacker advertises the SAME display name but its OWN (unpinned) handle.
        val attackerHandle = "age1attacker"
        assertEquals(PeerTrust.UNKNOWN, Pairing.classify(attackerHandle, store))
        assertFalse(
            Pairing.canSendOneTap(attackerHandle, store),
            "a spoofed name with an unpinned handle must never be one-tap sendable",
        )
    }

    @Test
    fun sas_isSymmetric_andDistinguishesPairs() {
        val a = "age1aaa"
        val b = "age1bbb"
        assertEquals(Sas.code(a, b), Sas.code(b, a), "SAS must not depend on order")
        assertEquals(6, Sas.code(a, b).length)
        assertNotEquals(Sas.code(a, b), Sas.code(a, "age1ccc"))
    }
}
