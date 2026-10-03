package com.relaypony.session.pairing

import com.relaypony.crypto.AgeProvider
import com.relaypony.session.OutgoingFile
import com.relaypony.session.SocketTransfer
import com.relaypony.transport.WireProtocol
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * LAN pairing (PROTOCOL_v3 section 4.5) over real loopback sockets: the PAIR frame, the listener
 * branching on PAIR versus HELLO, and de-duplication when the same message also arrives through
 * the relay.
 */
class LanPairingTests {
    private val provider = AgeProvider()
    private val servers = ArrayList<ServerSocket>()

    @AfterEach
    fun closeServers() = servers.forEach { runCatching { it.close() } }

    /**
     * A listener on 127.0.0.1 only. A wildcard socket on a busy machine can share its port number
     * with another process's loopback listener, which then takes the test's connections.
     */
    private fun loopbackServer(): ServerSocket =
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also { servers += it }

    /** One device: a pairing service, a real listener, and a queue its "main thread" drains. */
    private inner class Device(val name: String, val relays: HashMap<String, ArrayDeque<ByteArray>>, val alsoRelay: Boolean) {
        val identity = provider.generateIdentity()
        val handle = String(provider.recipientToQr(provider.recipientOf(identity)), Charsets.UTF_8)
        val inbox = InboxIds.generate()
        val trust = InMemoryTrustStore()
        val routes = InMemoryPeerRouteStore()
        val events = ArrayList<PairingService.Event>()
        val lanInbound = LinkedBlockingQueue<ByteArray>()
        val server = loopbackServer()
        /** Discovery stand-in: handle -> port of devices "seen" on the LAN with pr=1. */
        lateinit var lan: Map<String, Int>

        val service = PairingService(
            provider, identity, provider.scalarOf(identity), handle,
            myName = { name }, myInbox = { inbox }, myRelay = { "" },
            trust = trust, routes = routes,
            mailer = { peer, _, box, payload ->
                if (alsoRelay) relays.getOrPut(box) { ArrayDeque() }.addLast(payload)
                lan[peer]?.let { port -> LanPair.send("127.0.0.1", port, payload) }
            },
        ).also { s -> s.onEvent = { events += it } }

        init {
            thread(isDaemon = true, name = "accept-$name") {
                while (!server.isClosed) {
                    runCatching {
                        SocketTransfer.acceptOne(
                            server, provider, identity,
                            onPair = { lanInbound.put(it) },
                            firstFrameTimeoutMs = 1_000,      // a stray silent connection can't hold the listener
                        ) { ByteArrayOutputStream() }
                    }
                }
            }
        }

        /** Wait for one LAN PAIR frame and handle it on this thread, as the app's main thread would. */
        fun pumpLan(timeoutMs: Long = 10_000): Boolean {
            val got = lanInbound.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return false
            service.onLanPair(got)
            return true
        }

        fun pumpRelay() {
            val q = relays[inbox] ?: return
            q.toList().also { q.clear() }.forEach { s -> service.open(s)?.let { service.onSealedPlain(it) } }
        }
    }

    private fun pair(alsoRelay: Boolean): Pair<Device, Device> {
        val relays = HashMap<String, ArrayDeque<ByteArray>>()
        val a = Device("Phone A", relays, alsoRelay)
        val b = Device("Tablet B", relays, alsoRelay)
        a.lan = mapOf(b.handle to b.server.localPort)
        b.lan = mapOf(a.handle to a.server.localPort)
        return a to b
    }

    @Test
    fun qrPairing_overLanOnly_noRelayAtAll() {
        val (a, b) = pair(alsoRelay = false)
        b.service.onScanned(a.service.showQr().encode())
        val waiting = b.events.single() as PairingService.Event.Waiting

        assertTrue(a.pumpLan(), "PAIR_REQ never reached A over the LAN")
        val request = a.events.single() as PairingService.Event.Request
        assertEquals(waiting.code, request.pending.code)

        a.service.confirm(request.pending, accept = true)
        assertTrue(b.pumpLan(), "PAIR_ACK never reached B over the LAN")
        assertTrue(a.trust.isPinned(b.handle) && b.trust.isPinned(a.handle))
        assertEquals(PeerRoute(b.inbox, ""), a.routes.get(b.handle))
        assertEquals(PeerRoute(a.inbox, ""), b.routes.get(a.handle))
    }

    @Test
    fun sameMessageByLanAndRelay_isHandledOnce() {
        val (a, b) = pair(alsoRelay = true)
        b.service.onScanned(a.service.showQr().encode())
        assertTrue(a.pumpLan())
        a.pumpRelay()                                          // the relay copy of the same PAIR_REQ
        assertEquals(1, a.events.count { it is PairingService.Event.Request }, "one request, not two")

        a.service.confirm((a.events.single() as PairingService.Event.Request).pending, accept = true)
        assertTrue(b.pumpLan())
        b.pumpRelay()                                          // and of the same PAIR_ACK
        assertEquals(1, b.events.count { it is PairingService.Event.Paired }, "paired once, not twice")
    }

    @Test
    fun inboxAnnouncementOverLan_isIgnored() {
        val (a, b) = pair(alsoRelay = false)
        a.trust.pin(b.handle, "B", 0)
        val announcement = b.service.inboxAnnouncement(a.handle)
        LanPair.send("127.0.0.1", a.server.localPort, announcement)
        assertTrue(a.pumpLan())
        assertNull(a.routes.get(b.handle), "the LAN path only carries PAIR_REQ and PAIR_ACK")
    }

    @Test
    fun listenerStillReceivesFiles_afterBranchingOnTheFirstFrame() {
        val identity = provider.generateIdentity()
        val recipient = provider.recipientOf(identity)
        val server = loopbackServer()
        val payload = ByteArray(200_000) { it.toByte() }
        val got = ByteArrayOutputStream()
        var pairs = 0
        val t = thread {
            SocketTransfer.acceptOne(server, provider, identity, onPair = { pairs++ }) { got }
        }
        SocketTransfer.sendTo(
            "127.0.0.1", server.localPort, provider, listOf(recipient), "sender", "age1x",
            listOf(OutgoingFile("f.bin", "application/octet-stream", payload.size.toLong()) { ByteArrayInputStream(payload) }),
            peerMaxWire = 2,
        )
        t.join(10_000)
        assertArrayEquals(payload, got.toByteArray())
        assertEquals(0, pairs)
    }

    @Test
    fun oversizedPairFrame_isDroppedWithoutThrowing() {
        val identity = provider.generateIdentity()
        val server = loopbackServer()
        var pairs = 0
        var result: Any? = "unset"
        val t = thread {
            result = SocketTransfer.acceptOne(server, provider, identity, onPair = { pairs++ }) { ByteArrayOutputStream() }
        }
        Socket("127.0.0.1", server.localPort).use { s ->
            val out = s.getOutputStream()
            out.write(WireProtocol.PAIR.toInt())
            val len = WireProtocol.MAX_PAIR_PAYLOAD + 1
            out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
            out.flush()
        }
        t.join(5_000)
        assertNull(result)
        assertEquals(0, pairs)
    }

    @Test
    fun silentConnection_timesOutInsteadOfHoldingTheListener() {
        val identity = provider.generateIdentity()
        val server = loopbackServer()
        var result: Any? = "unset"
        var error: Throwable? = null
        val t = thread {
            runCatching {
                SocketTransfer.acceptOne(server, provider, identity, onPair = {}, firstFrameTimeoutMs = 300) { ByteArrayOutputStream() }
            }.onSuccess { result = it }.onFailure { error = it }
        }
        Socket("127.0.0.1", server.localPort).use {
            t.join(3_000)
            assertTrue(!t.isAlive, "listener still blocked on a silent connection")
        }
        assertNull(error, "a silent connection is not an error: $error")
        assertNull(result)
    }
}
