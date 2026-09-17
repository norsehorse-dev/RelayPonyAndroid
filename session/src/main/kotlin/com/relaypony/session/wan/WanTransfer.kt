package com.relaypony.session.wan

import android.os.Handler
import android.os.Looper
import com.ponydirect.PonyDirectWan
import com.relaypony.crypto.CryptoProvider
import com.relaypony.crypto.Identity
import com.relaypony.session.FileEntry
import com.relaypony.session.FileNames
import com.relaypony.session.FileSink
import com.relaypony.session.OutgoingFile
import com.relaypony.session.Session
import com.relaypony.transport.SignalBlob
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real file transfer over the WAN-direct path, for paired devices that aren't on the same LAN.
 * Twin of the Swift [com.relaypony.session] WanTransfer.
 *
 * It reuses the whole on-wire session unchanged: the transfer is serialized to one blob with
 * [Session.send] in its v1 monologue form (reverseIn = null), shipped over the reliable stream, and
 * replayed into [Session.receive] (reverseOut = null) on the far side — so crypto, framing and file
 * handling are identical to the LAN path; only the pipe differs. The pipe is chosen automatically
 * per peer: a direct PonyDirect hole-punched stream when one can be made, and the relay-forward
 * fallback ([RelayStreamChannel], E2E-encrypted, relay sees only ciphertext) when it can't. Both
 * sides run the same 12 s fallback timer and derive the same per-pair relay session nonce from their
 * sorted handles, so the only trigger a receiver needs is an incoming PonyDirect offer from a
 * *paired* peer.
 *
 * Threading: all job bookkeeping lives on the main thread (delegate callbacks and channel callbacks
 * are posted there). The blocking serialize/decode run on their own daemon threads. Received stream
 * bytes accumulate in a concurrent map written on PonyDirect's single exec thread.
 *
 * v1 note: the serialized transfer is buffered in memory on both ends (the WAN stream primitives are
 * blob-oriented). Fine for photos and documents; streaming very large files straight from disk over
 * WAN is a later optimization that won't change this wire format. All PonyDirect types stay inside
 * this class, so :app never depends on the PonyDirect library.
 */
class WanTransfer(
    private val provider: CryptoProvider,
    private val identity: Identity,
    private val myScalar: ByteArray,
    private val myHandle: String,
    private val deviceName: String,
    private val saveDir: () -> File,
    private val isPinned: (String) -> Boolean,
    stunHost: String = "api.carrierpony.com",
    stunPort: Int = 3478,
) {
    data class ReceivedFileInfo(val name: String, val size: Long, val mime: String, val path: String)
    data class ReceivedBatch(
        val peerHandle: String,
        val senderName: String,
        val senderHandle: String,
        val files: List<ReceivedFileInfo>,
    )

    private val main = Handler(Looper.getMainLooper())

    /** Per-peer send status ("Connecting…", "Sending…", "Sent", …). Posted to main. */
    var onSendStatus: (peer: String, status: WanStatus) -> Unit = { _, _ -> }
    /** The set of peers a send is currently in flight to. Posted to main. */
    var onSendingChanged: (Set<String>) -> Unit = {}
    /** A send finished (success or failure). Posted to main. */
    var onSendFinished: (peer: String, success: Boolean) -> Unit = { _, _ -> }
    /** A short status line for the receive UI. Posted to main. */
    var onReceiveStatus: (WanStatus) -> Unit = {}
    /** A WAN receive completed; the app files these into its inbox. Posted to main. */
    var onReceived: (ReceivedBatch) -> Unit = {}

    private val signaling = RelaySignaling(myHandle)

    private inner class SendJob(val blob: ByteArray) {
        var shipping = false
        var finished = false
        var fallback: Runnable? = null
        var relay: RelayStreamChannel? = null
    }

    private inner class RecvJob {
        var finished = false
        var fallback: Runnable? = null
        var relay: RelayStreamChannel? = null
    }

    // Accessed only on the main thread.
    private val sendJobs = HashMap<String, SendJob>()
    private val recvJobs = HashMap<String, RecvJob>()
    private val sending = HashSet<String>()

    // Written on PonyDirect's exec thread, drained on main at completion.
    private val recvBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()

    private val polling = AtomicBoolean(false)
    private var pollThread: Thread? = null
    private var receiveActive = false

    private val wan = PonyDirectWan(
        PonyDirectWan.StunServer(stunHost, stunPort),
        PonyPeerKeyProvider(myScalar, myHandle),
        signaling,
    ).apply {
        delegate = object : PonyDirectWan.Delegate {
            override fun onPathState(peerID: String, state: PonyDirectWan.PathState) {
                if (state == PonyDirectWan.PathState.CONNECTED) main.post { onConnected(peerID) }
            }
            override fun onPayload(peerID: String, payload: ByteArray) {}
            override fun onStreamBytes(peerID: String, bytes: ByteArray) {
                recvBuffers.getOrPut(peerID) { ByteArrayOutputStream() }.write(bytes)
            }
            override fun onStreamReceiveComplete(peerID: String) {
                val data = recvBuffers.remove(peerID)?.toByteArray() ?: ByteArray(0)
                main.post { finishReceive(peerID, data) }
            }
            override fun onStreamSendComplete(peerID: String, success: Boolean) {
                main.post { finishSend(peerID, success) }
            }
        }
    }

    // ---- Receive control ----

    /** Begin accepting incoming WAN transfers from paired peers. Safe to call repeatedly. */
    fun startReceiving() {
        if (receiveActive) return
        receiveActive = true
        onReceiveStatus(WanStatus(WanStatusKind.READY))
        startPolling()
    }

    /** Stop accepting new WAN transfers. In-flight receives finish. */
    fun stopReceiving() {
        receiveActive = false
        stopPollingIfIdle()
    }

    // ---- Send ----

    /** Send [files] to a paired peer over the internet: direct hole-punch first, relay fallback. */
    fun sendWAN(files: List<OutgoingFile>, peerHandle: String, senderName: String, senderHandle: String) {
        if (peerHandle.isEmpty()) return
        if (sendJobs.containsKey(peerHandle)) {
            onSendStatus(peerHandle, WanStatus(WanStatusKind.IN_PROGRESS))
            return
        }
        if (files.isEmpty()) {
            onSendStatus(peerHandle, WanStatus(WanStatusKind.ADD_FILES))
            return
        }
        onSendStatus(peerHandle, WanStatus(WanStatusKind.PREPARING))
        sending.add(peerHandle); onSendingChanged(sending.toSet())
        Thread {
            try {
                val recipient = provider.recipientFromQr(peerHandle.toByteArray(Charsets.UTF_8))
                val out = ByteArrayOutputStream()
                // v1 monologue: one-directional, no reverse HELLO.
                Session.send(provider, listOf(recipient), senderName, senderHandle, files, out, 1, null)
                val blob = out.toByteArray()
                main.post { beginSend(peerHandle, blob) }
            } catch (t: Throwable) {
                main.post {
                    onSendStatus(peerHandle, WanStatus(WanStatusKind.PREPARE_FAILED, arg = t.message))
                    sending.remove(peerHandle); onSendingChanged(sending.toSet())
                    onSendFinished(peerHandle, false)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /** True while a WAN send to [peer] is in progress. */
    fun isSending(peer: String): Boolean = sendJobs.containsKey(peer)

    private fun beginSend(peer: String, blob: ByteArray) {
        val job = SendJob(blob)
        sendJobs[peer] = job
        onSendStatus(peer, WanStatus(WanStatusKind.CONNECTING))
        startPolling()
        // Clear any session left over from a previous transfer (send OR receive) so open()
        // builds a fresh initiator session instead of reusing a finished one — open() no-ops
        // when a session already exists.
        wan.close(peer)
        // The sender always initiates; the receiver auto-responds when the offer arrives via
        // handleSignal. (Tying the role to handle order would deadlock when the sender sorts
        // higher: it would open as responder and wait for an offer that is never sent.)
        wan.open(peer, PonyDirectWan.Role.INITIATOR)
        val r = Runnable {
            val j = sendJobs[peer]
            if (j != null && !j.shipping) shipViaRelay(peer)
        }
        job.fallback = r
        main.postDelayed(r, 12000)
    }

    private fun shipDirect(peer: String) {
        val job = sendJobs[peer] ?: return
        if (job.shipping) return
        job.shipping = true
        job.fallback?.let { main.removeCallbacks(it) }
        onSendStatus(peer, WanStatus(WanStatusKind.SENDING))
        wan.openStream(peer)
        wan.writeStream(job.blob, peer)
        wan.finishStream(peer)
    }

    private fun shipViaRelay(peer: String) {
        val job = sendJobs[peer] ?: return
        if (job.shipping) return
        val key = PonyPeerKeyProvider(myScalar, myHandle).pairKey(peer) ?: return
        job.shipping = true
        onSendStatus(peer, WanStatus(WanStatusKind.SENDING_RELAY))
        val ch = RelayStreamChannel(key, myHandle, peer)
        ch.onSendComplete = { main.post { finishSend(peer, true) } }
        job.relay = ch
        ch.send(job.blob)
    }

    private fun finishSend(peer: String, success: Boolean) {
        val job = sendJobs[peer] ?: return
        if (job.finished) return
        job.finished = true
        job.fallback?.let { main.removeCallbacks(it) }
        job.relay?.stop()
        sendJobs.remove(peer)
        sending.remove(peer); onSendingChanged(sending.toSet())
        wan.close(peer)   // fresh session for the next transfer to this peer
        onSendStatus(peer, WanStatus(if (success) WanStatusKind.SENT else WanStatusKind.SEND_FAILED))
        onSendFinished(peer, success)
        stopPollingIfIdle()
    }

    private fun onConnected(peer: String) {
        sendJobs[peer]?.let { if (!it.shipping) shipDirect(peer) }
        recvJobs[peer]?.fallback?.let { main.removeCallbacks(it) }
    }

    // ---- Receive finalize ----

    private fun finishReceive(peer: String, blob: ByteArray) {
        val rj = recvJobs[peer] ?: RecvJob().also { recvJobs[peer] = it }
        if (rj.finished) return
        rj.finished = true
        rj.fallback?.let { main.removeCallbacks(it) }
        recvJobs.remove(peer)
        decodeAndFile(peer, blob)
    }

    private fun decodeAndFile(peer: String, blob: ByteArray) {
        onReceiveStatus(WanStatus(WanStatusKind.RECEIVING))
        Thread {
            try {
                val dir = saveDir().apply { mkdirs() }
                val written = ArrayList<ReceivedFileInfo>()
                val result = Session.receive(
                    provider,
                    identity,
                    ByteArrayInputStream(blob),
                    FileSink { entry: FileEntry ->
                        val outFile = uniqueFile(dir, FileNames.sanitize(entry.name))
                        written.add(ReceivedFileInfo(outFile.name, entry.size, entry.mime, outFile.absolutePath))
                        outFile.outputStream()
                    },
                    null,
                    deviceName,
                    myHandle,
                )
                val batch = ReceivedBatch(peer, result.senderName, result.senderHandle, written)
                main.post {
                    onReceived(batch)
                    onReceiveStatus(WanStatus(WanStatusKind.RECEIVED, arg = result.senderName, count = written.size))
                    stopPollingIfIdle()
                }
            } catch (t: Throwable) {
                main.post {
                    onReceiveStatus(WanStatus(WanStatusKind.RECEIVE_FAILED, arg = t.message))
                    stopPollingIfIdle()
                }
            }
        }.apply { isDaemon = true; start() }
    }

    // ---- Polling / ingest ----

    private fun startPolling() {
        if (!polling.compareAndSet(false, true)) return
        pollThread = Thread {
            while (polling.get()) {
                val blobs = signaling.poll()
                if (blobs.isNotEmpty()) main.post { ingest(blobs) }
                try { Thread.sleep(1500) } catch (e: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    /** Feed inbound blobs to the path, but only offers from *paired* peers; arm a per-peer
     *  relay-receive fallback the first time a paired peer reaches out. Runs on the main thread. */
    private fun ingest(blobs: List<String>) {
        for (line in blobs) {
            val b = runCatching { SignalBlob.decode(line) }.getOrNull() ?: continue
            if (b.to != myHandle || !isPinned(b.from)) continue     // trust gate
            // First blob of a new incoming transfer (no live recv job): tear down any
            // session left over from a previous one so the offer builds a fresh session.
            if (!sendJobs.containsKey(b.from) && !recvJobs.containsKey(b.from)) {
                wan.close(b.from)
                ensureRecvJob(b.from)
            }
            runCatching { signaling.importBlob(line, wan) }
        }
    }

    private fun ensureRecvJob(peer: String) {
        if (recvJobs.containsKey(peer)) return
        val rj = RecvJob()
        recvJobs[peer] = rj
        val r = Runnable {
            val j = recvJobs[peer]
            if (j != null && !j.finished && j.relay == null) armRelayReceive(peer)
        }
        rj.fallback = r
        main.postDelayed(r, 12000)
    }

    private fun armRelayReceive(peer: String) {
        val rj = recvJobs[peer] ?: return
        if (rj.relay != null) return
        val key = PonyPeerKeyProvider(myScalar, myHandle).pairKey(peer) ?: return
        onReceiveStatus(WanStatus(WanStatusKind.RECEIVING_RELAY))
        val ch = RelayStreamChannel(key, myHandle, peer)
        ch.onComplete = { data ->
            main.post {
                val j = recvJobs[peer]
                if (j != null && !j.finished) {
                    j.finished = true
                    recvJobs.remove(peer)
                    decodeAndFile(peer, data)
                }
            }
        }
        rj.relay = ch
        ch.start()
    }

    private fun stopPollingIfIdle() {
        if (receiveActive || sendJobs.isNotEmpty() || recvJobs.isNotEmpty()) return
        polling.set(false)
        pollThread?.interrupt()
        pollThread = null
    }

    /** Stop everything (call when the owner is torn down). */
    fun stop() {
        receiveActive = false
        polling.set(false)
        pollThread?.interrupt()
        pollThread = null
        sendJobs.values.forEach { it.relay?.stop() }
        recvJobs.values.forEach { it.relay?.stop() }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }
}
