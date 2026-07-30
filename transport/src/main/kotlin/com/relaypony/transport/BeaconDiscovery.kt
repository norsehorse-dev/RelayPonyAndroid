package com.relaypony.transport

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The socket half of [Beacon]: broadcast discovery that works on the networks mDNS gives up on.
 *
 * Pure `java.net`, so the phones and the desktop run this identical file. The whole point is
 * per-interface control, which is the thing Android's NsdManager does not give you: every datagram
 * we send goes out of a socket bound to a specific local address, so a phone sharing its hotspot
 * speaks on the tethered subnet instead of on whatever the OS has decided is the default network.
 *
 * Two sockets, two jobs:
 *
 *  * The **listener** binds the well-known port on the wildcard address. It answers PROBEs (unicast,
 *    straight back to the asker) and picks up the periodic broadcast ANNOUNCEs of anyone already
 *    receiving.
 *  * A **prober** is a short-lived socket per interface, bound to that interface's address. It
 *    broadcasts a PROBE and reads the unicast replies itself, which keeps replies unambiguous even
 *    when several instances share a machine.
 *
 * Android callers must hold a `WifiManager.MulticastLock` while listening — without it the platform
 * drops inbound broadcast frames that aren't addressed to the device.
 */
class BeaconDiscovery {

    data class Peer(
        val name: String,
        val host: String,
        val port: Int,
        val recipientHandle: String,
        val maxWire: Int = 1,
        /** The interface this peer was heard on — useful for diagnostics and for choosing a route. */
        val via: String = "",
    )

    private class Advert(
        val tcpPort: Int,
        val deviceName: String,
        val handle: String,
        val maxWire: Int,
    )

    @Volatile private var advert: Advert? = null
    @Volatile private var listener: DatagramSocket? = null
    @Volatile private var running = false
    private val threads = CopyOnWriteArrayList<Thread>()

    /** Problems worth showing a user, in plain words. Bounded so a flapping link can't grow it. */
    val problems = CopyOnWriteArrayList<String>()

    /** Our own handle, so we ignore our own broadcasts instead of discovering ourselves. */
    @Volatile private var selfHandle: String? = null

    /**
     * Start answering PROBEs and collecting ANNOUNCEs. Safe to call once; later calls are ignored.
     * [onPeer] fires on a background thread, possibly repeatedly for the same peer.
     */
    @Synchronized
    fun listen(selfHandle: String?, onPeer: (Peer) -> Unit) {
        if (running) return
        this.selfHandle = selfHandle
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(Beacon.PORT))
            }
        }.getOrElse {
            note("Beacon could not bind UDP ${Beacon.PORT}: ${reason(it)} — active search still works.")
            return
        }
        listener = socket
        running = true
        spawn("relaypony-beacon-listen") {
            val buf = ByteArray(Beacon.MAX_FRAME)
            while (running && !socket.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                val ok = runCatching { socket.receive(packet) }.isSuccess
                if (!ok) { if (running && !socket.isClosed) continue else break }
                when (val msg = Beacon.decode(packet.data, packet.length)) {
                    is Beacon.Message.Probe -> replyTo(packet.address, packet.port)
                    is Beacon.Message.Announce -> emit(msg, packet.address, "", onPeer)
                    null -> Unit                                   // not ours; the port is shared
                }
            }
        }
        spawn("relaypony-beacon-announce") {
            while (running) {
                if (advert != null) broadcastAnnounce()
                Thread.sleep(ANNOUNCE_INTERVAL_MS)
            }
        }
    }

    /**
     * Start replying to probes and broadcasting our presence. Call again to update the details.
     *
     * [maxWire] is the highest wire version this build actually speaks, and it is a parameter
     * rather than a constant read from [WireProtocol] on purpose: the two apps do not ship the same
     * transport revision, so each has to state its own truth. Announcing a version we cannot speak
     * would make a peer negotiate up and fail.
     */
    fun advertise(
        tcpPort: Int,
        deviceName: String,
        recipientHandle: String,
        maxWire: Int = WireProtocol.WIRE_VERSION,
    ) {
        advert = Advert(tcpPort, deviceName, recipientHandle, maxWire)
        selfHandle = recipientHandle
        broadcastAnnounce()                                        // don't make anyone wait a tick
    }

    fun stopAdvertising() {
        advert = null
    }

    /**
     * Actively look for peers: broadcast a PROBE out of every interface and gather replies for
     * [timeoutMs]. Blocks the calling thread for roughly that long, so call it off the UI thread.
     */
    fun probe(timeoutMs: Long = 1500, onPeer: (Peer) -> Unit) {
        val endpoints = LocalInterfaces.endpoints()
        if (endpoints.isEmpty()) {
            note("No connected network interface — nothing to search.")
            return
        }
        val workers = endpoints.map { endpoint ->
            Thread({ probeOn(endpoint, timeoutMs, onPeer) }, "relaypony-beacon-probe-${endpoint.ifaceName}")
                .apply { isDaemon = true; start() }
        }
        workers.forEach { runCatching { it.join(timeoutMs + 750) } }
    }

    private fun probeOn(endpoint: LocalInterfaces.Endpoint, timeoutMs: Long, onPeer: (Peer) -> Unit) {
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(endpoint.address, 0))       // pin the egress interface
                soTimeout = PROBE_POLL_MS
            }
        }.getOrElse {
            note("Beacon probe failed on ${endpoint.ifaceName}: ${reason(it)}")
            return
        }
        // Explicit try/finally rather than `use`: DatagramSocket only became Closeable in a later
        // Android API than this module's minimum, and this file is compiled for both platforms.
        try {
            val frame = Beacon.encodeProbe()
            for (target in broadcastTargets(endpoint)) {
                runCatching { socket.send(DatagramPacket(frame, frame.size, target, Beacon.PORT)) }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(Beacon.MAX_FRAME)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                if (runCatching { socket.receive(packet) }.isFailure) continue    // soTimeout tick
                val msg = Beacon.decode(packet.data, packet.length)
                if (msg is Beacon.Message.Announce) {
                    emit(msg, packet.address, endpoint.ifaceName, onPeer)
                }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun replyTo(address: InetAddress, port: Int) {
        val a = advert ?: return                                   // not receiving: stay quiet
        if (port <= 0) return
        val frame = runCatching {
            Beacon.encodeAnnounce(a.tcpPort, a.maxWire, a.deviceName, a.handle)
        }.getOrNull() ?: return
        val sock = listener ?: return
        runCatching { sock.send(DatagramPacket(frame, frame.size, address, port)) }
    }

    private fun broadcastAnnounce() {
        val a = advert ?: return
        val frame = runCatching {
            Beacon.encodeAnnounce(a.tcpPort, a.maxWire, a.deviceName, a.handle)
        }.getOrNull() ?: return
        for (endpoint in LocalInterfaces.endpoints()) {
            val sock = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(endpoint.address, 0))
                }
            }.getOrNull() ?: continue
            try {
                for (target in broadcastTargets(endpoint)) {
                    runCatching { sock.send(DatagramPacket(frame, frame.size, target, Beacon.PORT)) }
                }
            } finally {
                runCatching { sock.close() }
            }
        }
    }

    /** Directed broadcast first (routes precisely), then limited broadcast as a catch-all. */
    private fun broadcastTargets(endpoint: LocalInterfaces.Endpoint): List<InetAddress> {
        val out = ArrayList<InetAddress>(2)
        LocalInterfaces.broadcastFor(endpoint)?.let { out.add(it) }
        runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull()?.let {
            if (out.none { existing -> existing == it }) out.add(it)
        }
        return out
    }

    private fun emit(msg: Beacon.Message.Announce, from: InetAddress, via: String, onPeer: (Peer) -> Unit) {
        if (msg.recipientHandle == selfHandle) return              // that's us
        val host = from.hostAddress ?: return
        onPeer(Peer(msg.deviceName, host, msg.tcpPort, msg.recipientHandle, msg.maxWire, via))
    }

    @Synchronized
    fun close() {
        running = false
        advert = null
        runCatching { listener?.close() }
        listener = null
        threads.forEach { runCatching { it.interrupt() } }
        threads.clear()
    }

    private fun spawn(name: String, body: () -> Unit) {
        val t = Thread({ runCatching { body() } }, name).apply { isDaemon = true }
        threads.add(t)
        t.start()
    }

    private fun note(message: String) {
        if (problems.size < MAX_PROBLEMS && message !in problems) problems.add(message)
    }

    private fun reason(t: Throwable): String = t.message ?: t.javaClass.simpleName

    private companion object {
        const val ANNOUNCE_INTERVAL_MS = 4_000L
        const val PROBE_POLL_MS = 250
        const val MAX_PROBLEMS = 8
    }
}
