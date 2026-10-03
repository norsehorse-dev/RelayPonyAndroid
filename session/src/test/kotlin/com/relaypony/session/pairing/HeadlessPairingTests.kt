package com.relaypony.session.pairing

import com.relaypony.crypto.AgeProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P1 exit test: two complete clients pair over a relay with no UI. The relay here is an in-memory
 * stand-in with the same mailbox contract as RelayPony-Relay 2.0 (delete-on-fetch queues keyed by
 * relay and mailbox id); RelayPony-Relay's tests/e2e_pairing.py runs the same flow against the real
 * PHP relay.
 */
class HeadlessPairingTests {
    private val provider = AgeProvider()

    /** relay base URL -> mailbox id -> queued payloads. */
    private class FakeRelays {
        val boxes = HashMap<String, HashMap<String, ArrayDeque<ByteArray>>>()
        fun send(relay: String, inbox: String, payload: ByteArray) {
            boxes.getOrPut(relay) { HashMap() }.getOrPut(inbox) { ArrayDeque() }.addLast(payload)
        }
        fun poll(relay: String, inbox: String): List<ByteArray> {
            val q = boxes[relay]?.get(inbox) ?: return emptyList()
            return q.toList().also { q.clear() }
        }
    }

    private inner class Client(val name: String, val relay: String, val relays: FakeRelays, var clock: Long = 0) {
        val identity = provider.generateIdentity()
        val handle = String(provider.recipientToQr(provider.recipientOf(identity)), Charsets.UTF_8)
        var inbox = InboxIds.generate()
        val trust = InMemoryTrustStore()
        val routes = InMemoryPeerRouteStore()
        val events = ArrayList<PairingService.Event>()
        val service = PairingService(
            provider, identity, provider.scalarOf(identity), handle,
            myName = { name }, myInbox = { inbox }, myRelay = { relay },
            trust = trust, routes = routes,
            mailer = { _, r, box, payload -> relays.send(r, box, payload) },
            now = { clock },
        ).also { s -> s.onEvent = { events += it } }

        /** One poll of this device's own inbox, as the app's poll loop would do. */
        fun pump(): Int {
            val got = relays.poll(relay, inbox)
            got.forEach { sealed -> service.open(sealed)?.let { service.onSealedPlain(it) } }
            return got.size
        }
    }

    @Test
    fun qrPairing_endToEnd_onDifferentRelays() {
        val relays = FakeRelays()
        val a = Client("Phone A", "", relays)
        val b = Client("Tablet B", "https://relay.example.com", relays)

        val qr = a.service.showQr().encode()
        b.service.onScanned(qr)
        val waiting = b.events.single() as PairingService.Event.Waiting

        assertEquals(1, a.pump())
        val request = a.events.single() as PairingService.Event.Request
        assertEquals(waiting.code, request.pending.code)   // same 8 digits on both screens

        a.service.confirm(request.pending, accept = true)
        assertEquals(1, b.pump())

        assertTrue(a.trust.isPinned(b.handle) && b.trust.isPinned(a.handle))
        assertEquals(PeerRoute(b.inbox, "https://relay.example.com"), a.routes.get(b.handle))
        assertEquals(PeerRoute(a.inbox, ""), b.routes.get(a.handle))
        assertEquals("Phone A", b.trust.get(a.handle)!!.name)
        assertTrue(b.events.last() is PairingService.Event.Paired)
    }

    @Test
    fun declineLeavesBothUnpinned() {
        val relays = FakeRelays()
        val a = Client("A", "", relays); val b = Client("B", "", relays)
        b.service.onScanned(a.service.showQr().encode())
        a.pump()
        a.service.confirm((a.events.single() as PairingService.Event.Request).pending, accept = false)
        b.pump()
        assertFalse(a.trust.isPinned(b.handle) || b.trust.isPinned(a.handle))
        assertTrue(b.events.last() is PairingService.Event.Declined)
    }

    @Test
    fun attackerWhoSawTheQr_cantMatchTheCode_andSpendsTheNonce() {
        val relays = FakeRelays()
        val a = Client("A", "", relays); val b = Client("B", "", relays); val m = Client("M", "", relays)
        val qr = a.service.showQr().encode()
        m.service.onScanned(qr)          // the attacker gets there first
        b.service.onScanned(qr)
        a.pump()
        val req = a.events.filterIsInstance<PairingService.Event.Request>()
        assertEquals(1, req.size)        // only the first request is shown
        val bCode = (b.events.single() as PairingService.Event.Waiting).code
        assertFalse(req.single().pending.code == bCode)   // so the user sees a mismatch and cancels
    }

    @Test
    fun screenshotOfAnOldQr_cantPair() {
        val relays = FakeRelays()
        val a = Client("A", "", relays); val b = Client("B", "", relays)
        val old = a.service.showQr().encode()
        a.clock = PairingFlow.VALIDITY_MS + 1
        b.clock = a.clock
        b.service.onScanned(old)
        a.pump()
        assertTrue(a.events.none { it is PairingService.Event.Request })
    }

    @Test
    fun inboxAnnouncement_updatesRouteForPinnedPeersOnly() {
        val relays = FakeRelays()
        val a = Client("A", "", relays); val b = Client("B", "", relays); val m = Client("M", "", relays)
        b.service.onScanned(a.service.showQr().encode()); a.pump()
        a.service.confirm((a.events.single() as PairingService.Event.Request).pending, true); b.pump()

        a.inbox = InboxIds.generate()      // A rotates its inbox and tells B
        relays.send("", b.inbox, a.service.inboxAnnouncement(b.handle))
        relays.send("", b.inbox, m.service.inboxAnnouncement(b.handle))   // unpaired M tries too
        b.pump()
        assertEquals(PeerRoute(a.inbox, ""), b.routes.get(a.handle))
        assertEquals(null, b.routes.get(m.handle))
    }

    /** Pair [a] (shows the QR) with [b] (scans it) over the fake relays. */
    private fun pair(a: Client, b: Client) {
        b.service.onScanned(a.service.showQr().encode()); a.pump()
        a.service.confirm(a.events.filterIsInstance<PairingService.Event.Request>().last().pending, true); b.pump()
        assertTrue(a.trust.isPinned(b.handle) && b.trust.isPinned(a.handle))
    }

    @Test
    fun unpair_removesBothSides() {
        val relays = FakeRelays()
        val a = Client("A", "", relays, clock = 1_000); val b = Client("B", "https://relay.example.com", relays, clock = 1_000)
        pair(a, b)

        a.clock = 2_000
        val notice = a.service.unpairNotice(b.handle)
        val route = a.routes.get(b.handle)!!
        a.service.forget(b.handle)
        relays.send(route.relay, route.inboxId, notice)
        assertFalse(a.trust.isPinned(b.handle))
        assertEquals(null, a.routes.get(b.handle))

        b.clock = 2_000
        assertEquals(1, b.pump())
        assertFalse(b.trust.isPinned(a.handle))
        assertEquals(null, b.routes.get(a.handle))
        val ev = b.events.last() as PairingService.Event.Unpaired
        assertEquals(a.handle, ev.handle)
        assertEquals("A", ev.name)
    }

    @Test
    fun unpairNotice_replayedAfterRepairing_isIgnored() {
        val relays = FakeRelays()
        val a = Client("A", "", relays, clock = 1_000); val b = Client("B", "", relays, clock = 1_000)
        pair(a, b)
        a.clock = 2_000
        val old = a.service.unpairNotice(b.handle)
        relays.send("", b.inbox, old); b.clock = 2_000; b.pump()
        assertFalse(b.trust.isPinned(a.handle))

        // They pair again later; someone replays the captured notice.
        a.clock = 3_000; b.clock = 3_000
        a.service.forget(b.handle)
        pair(a, b)
        relays.send("", b.inbox, old); b.pump()
        assertTrue(b.trust.isPinned(a.handle))
    }

    @Test
    fun unpairNotice_fromAnUnpairedDevice_isIgnored() {
        val relays = FakeRelays()
        val a = Client("A", "", relays); val b = Client("B", "", relays); val m = Client("M", "", relays)
        pair(a, b)
        m.clock = 10_000
        relays.send("", b.inbox, m.service.unpairNotice(b.handle))
        b.clock = 10_000; b.pump()
        assertTrue(b.trust.isPinned(a.handle))
        assertTrue(b.events.none { it is PairingService.Event.Unpaired })
    }

    @Test
    fun unpairNotice_isHonouredOnTheLanPath() {
        val relays = FakeRelays()
        val a = Client("A", "", relays, clock = 1_000); val b = Client("B", "", relays, clock = 1_000)
        pair(a, b)
        a.clock = 2_000; b.clock = 2_000
        b.service.onLanPair(a.service.unpairNotice(b.handle))
        assertFalse(b.trust.isPinned(a.handle))
    }

    @Test
    fun legacyV1Qr_pinsOneWay() {
        val relays = FakeRelays()
        val b = Client("B", "", relays)
        val legacy = QrPayload(1, 0x01, "age1q73he0q5yzfu3d64msd3p6rvksnrwjk3d2598mgtmlqt9wrdr37q2vrn72", "Old Phone").encode()
        b.service.onScanned(legacy)
        assertTrue(b.events.single() is PairingService.Event.PairedOneWay)
        assertTrue(b.trust.isPinned("age1q73he0q5yzfu3d64msd3p6rvksnrwjk3d2598mgtmlqt9wrdr37q2vrn72"))
    }
}
