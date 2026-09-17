package com.relaypony.session.wan

import android.os.Handler
import android.os.Looper
import com.ponydirect.PonyDirectWan
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives the WAN-direct dev screen over the relay. Owns a PonyDirectWan wired to PonyPeerKeyProvider
 * and the relay-backed [RelaySignaling], and reports state through plain callbacks (posted to the
 * main thread). A background poll loop pulls inbound signaling blobs off the relay and feeds them to
 * the path, so connecting is automatic — the user just enters the peer handle and taps Connect.
 * All PonyDirect types stay inside this controller, so :app never depends on the PonyDirect lib.
 */
class WanDirectDevController(
    private val myScalar: ByteArray,
    private val myHandle: String,
    stunHost: String = "api.carrierpony.com",
    stunPort: Int = 3478,
) {
    private val main = Handler(Looper.getMainLooper())

    var onStatus: (String) -> Unit = {}
    var onReceived: (String) -> Unit = {}
    var onStreamSent: (String) -> Unit = {}
    var onStreamRecv: (String) -> Unit = {}
    var onDiag: (String) -> Unit = {}
    var onRelaySent: (String) -> Unit = {}
    var onRelayRecv: (String) -> Unit = {}
    var relayActive: Boolean = false
        private set
    var onUsingRelay: (Boolean) -> Unit = {}
    @Volatile private var connected = false
    private var lastPeer = ""

    private val signaling = RelaySignaling(myHandle)
    private val streamRecvBuffer = ByteArrayOutputStream()   // written only on the WAN thread

    private val polling = AtomicBoolean(false)
    private var pollThread: Thread? = null

    private val wan = PonyDirectWan(
        PonyDirectWan.StunServer(stunHost, stunPort),
        PonyPeerKeyProvider(myScalar, myHandle),
        signaling,
    ).apply {
        delegate = object : PonyDirectWan.Delegate {
            override fun onPathState(peerID: String, state: PonyDirectWan.PathState) {
                connected = state == PonyDirectWan.PathState.CONNECTED
                if (connected) { stopPolling(); main.removeCallbacks(fallbackRunnable); main.post { onUsingRelay(false) } }
                main.post { onStatus(state.name) }
            }
            override fun onPayload(peerID: String, payload: ByteArray) {
                main.post { onReceived(String(payload)) }
            }
            override fun onStreamBytes(peerID: String, bytes: ByteArray) {
                streamRecvBuffer.write(bytes)
            }
            override fun onStreamReceiveComplete(peerID: String) {
                val data = streamRecvBuffer.toByteArray()
                streamRecvBuffer.reset()
                main.post { onStreamRecv("received ${data.size} bytes, sha ${sha8(data)}") }
            }
        }
    }

    init {
        startPolling()   // both sides poll from the moment the screen opens
    }

    /** Background loop: pull blobs addressed to us off the relay and feed them to the path. */
    private fun startPolling() {
        if (!polling.compareAndSet(false, true)) return
        pollThread = Thread {
            while (polling.get()) {
                for (b in signaling.poll()) runCatching { signaling.importBlob(b, wan) }
                main.post { onDiag(wan.diagnostics() + "\n" + signaling.stats) }
                try { Thread.sleep(1500) } catch (e: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopPolling() {
        polling.set(false)
        pollThread?.interrupt()
        pollThread = null
    }

    /** The smaller handle initiates; the relay carries the handshake both ways, so both devices can
     *  just tap Connect regardless of order. */
    fun openPath(peerHandle: String) {
        if (peerHandle.isEmpty()) return
        val role = if (myHandle < peerHandle) PonyDirectWan.Role.INITIATOR else PonyDirectWan.Role.RESPONDER
        wan.open(peerHandle, role)
        startPolling()
        lastPeer = peerHandle
        main.removeCallbacks(fallbackRunnable)
        main.postDelayed(fallbackRunnable, 12000)
    }

    fun send(peerHandle: String, text: String) {
        if (peerHandle.isEmpty()) return
        wan.sendPayload(text.toByteArray(), peerHandle)
    }

    private var relayChannel: RelayStreamChannel? = null
    private val fallbackRunnable = Runnable {
        if (!connected && lastPeer.isNotEmpty()) { enableRelayFallback(lastPeer); main.post { onUsingRelay(true) } }
    }

    /** Start the relay-forward fallback channel (both sides call this to send/receive over the
     *  relay when a direct punch is impossible). */
    fun enableRelayFallback(peerHandle: String) {
        if (relayChannel != null || peerHandle.isEmpty()) return
        val key = PonyPeerKeyProvider(myScalar, myHandle).pairKey(peerHandle) ?: return
        val ch = RelayStreamChannel(key, myHandle, peerHandle)
        ch.onProgress = { n -> onRelayRecv("receiving\u2026 $n bytes") }
        ch.onComplete = { d -> onRelayRecv("received ${d.size} bytes, sha ${sha8(d)}") }
        ch.onSendComplete = { onRelaySent("delivered") }
        relayChannel = ch
        relayActive = true
        ch.start()
    }

    /** Send a 1 MB test blob over the relay-forward fallback. */
    fun sendStreamViaRelay(peerHandle: String, sizeKB: Int = 1024) {
        enableRelayFallback(peerHandle)
        val ch = relayChannel ?: return
        val blob = ByteArray(sizeKB * 1024) { (it % 251).toByte() }
        onRelaySent("sending ${blob.size} bytes, sha ${sha8(blob)}")
        ch.send(blob)
    }

    /** Push a deterministic 1 MB blob over the reliable stream (well past the 256 KB message cap). */
    fun sendStreamTest(peerHandle: String, sizeKB: Int = 1024) {
        if (peerHandle.isEmpty()) return
        val blob = ByteArray(sizeKB * 1024) { (it % 251).toByte() }
        main.post { onStreamSent("sent ${blob.size} bytes, sha ${sha8(blob)}") }
        wan.openStream(peerHandle)
        wan.writeStream(blob, peerHandle)
        wan.finishStream(peerHandle)
    }

    /** Stop the poll loop (call when the screen goes away). */
    fun stop() { stopPolling(); relayChannel?.stop() }

    private fun sha8(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
