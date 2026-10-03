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
import com.relaypony.session.TransferLimits
import com.relaypony.transport.SignalBlob
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
    /** Where inbound WAN transfers spool to disk before decoding. Ciphertext at rest. */
    private val spoolDir: () -> File = { File(saveDir().parentFile ?: saveDir(), "wan-spool") },
    /** Receive ceilings. :app passes free space for the inbox volume. */
    private val limits: () -> TransferLimits = { TransferLimits.DEFAULT },
    /** True once a peer has sent us a sealed blob (persisted by :app). Such a peer gets sealed
     *  blobs only, and its plaintext RPS1 blobs are dropped as a downgrade. */
    private val isSealedPeer: (String) -> Boolean = { false },
    private val markSealedPeer: (String) -> Unit = {},
) {
    companion object {
        /**
         * Largest WAN send. Sends stream from disk (4.0), so this is no longer a memory limit: it is
         * the receiver's per-transfer ceiling, refused up front instead of after the receiver has
         * spooled most of it.
         */
        const val MAX_WAN_SEND_BYTES: Long = TransferLimits.MAX_TRANSFER_BYTES
    }

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
    /** Bytes handed to the stream so far out of the files' total, on the main thread, a few times a second. */
    var onSendProgress: (peer: String, sent: Long, total: Long) -> Unit = { _, _, _ -> }
    /** A short status line for the receive UI. Posted to main. */
    var onReceiveStatus: (WanStatus) -> Unit = {}
    /** A WAN receive completed; the app files these into its inbox. Posted to main. */
    var onReceived: (ReceivedBatch) -> Unit = {}

    /**
     * 4.0 private inboxes (PROTOCOL_v3.md section 5). The app implements these; the defaults keep
     * 3.x behaviour (no inbox, handle addressing only). Called from background threads unless noted.
     */
    interface InboxHooks {
        /** This device's inbox id, or null to not poll one (a 1.x relay without mailboxes). */
        fun myInbox(): String? = null
        /** The relay client for this device's own relay, used to poll [myInbox]. */
        fun myRelayClient(): RelayClient? = null
        /** A paired peer's inbox and relay, if known. */
        fun route(peer: String): com.relaypony.session.pairing.PeerRoute? = null
        /** A client for a normalized relay value ("" is the default relay). */
        fun clientFor(relay: String): RelayClient? = null
        /** The sealed announcement of this device's inbox for [peer], or null to skip. */
        fun announcement(peer: String): ByteArray? = null
        /** True once [peer] has been seen reaching this device through its inbox. */
        fun peerUsesMyInbox(peer: String): Boolean = true
        fun markPeerUsesMyInbox(peer: String) {}
    }

    @Volatile var hooks: InboxHooks = object : InboxHooks {}

    /**
     * A sealed message from this device's inbox (or its handle queue) that isn't signaling: pairing
     * requests and ACKs and inbox announcements, as plaintext lines. Posted to main. The app hands
     * these to its PairingService.
     */
    var onInboxMessage: (String) -> Unit = {}

    /** Keep polling while something other than a transfer needs the inbox (the pair sheet). */
    @Volatile private var held = false

    fun holdPolling(on: Boolean) {
        held = on
        if (on) startPolling() else main.post { stopPollingIfIdle() }
    }

    private val signaling = RelaySignaling(
        myHandle,
        sealer = { blob -> SealedSignal.seal(provider, blob, myScalar, myHandle) },
        sealedPeer = isSealedPeer,
        route = { peer -> hooks.route(peer) },
        mboxSender = { relay, inbox, bytes ->
            (hooks.clientFor(relay) ?: throw IllegalStateException("no relay client")).mboxSend(inbox, bytes)
        },
    )

    /** One outgoing WAN transfer. The files are encrypted while they are sent, never ahead of time. */
    private inner class SendJob(
        val files: List<OutgoingFile>,
        val senderName: String,
        val senderHandle: String,
    ) {
        var shipping = false
        var finished = false
        var fallback: Runnable? = null
        var relay: RelayStreamChannel? = null
        /** Set when the job ends for any reason; the producer thread stops at its next write. */
        @Volatile var cancelled = false
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

    // Written on PonyDirect's exec thread, handed to main at completion. One disk spool per peer
    // (4.0: replaces the 3.0 in-memory buffer). A peer lands in [abortedRecv] when its spool hits
    // a ceiling, so the rest of that stream is dropped until it completes.
    private val recvSpools = ConcurrentHashMap<String, WanSpool>()
    private val abortedRecv: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        WanSpool.sweep(spoolDir())
    }

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
                if (peerID in abortedRecv) return
                try {
                    val spool = recvSpools.getOrPut(peerID) { WanSpool.create(spoolDir(), limits()) }
                    spool.write(bytes)
                } catch (t: Throwable) {
                    abortedRecv.add(peerID)
                    recvSpools.remove(peerID)?.discard()
                    main.post { abortReceive(peerID, t) }
                }
            }
            override fun onStreamReceiveComplete(peerID: String) {
                if (abortedRecv.remove(peerID)) return
                val spool = recvSpools.remove(peerID)
                    ?: runCatching { WanSpool.create(spoolDir(), limits()) }.getOrNull()
                    ?: return
                val file = runCatching { spool.finish() }.getOrElse { t ->
                    spool.discard(); main.post { abortReceive(peerID, t) }; return
                }
                main.post { finishReceive(peerID, file) }
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
        val total = files.sumOf { it.size }
        if (total > MAX_WAN_SEND_BYTES) {
            onSendStatus(peerHandle, WanStatus(
                WanStatusKind.PREPARE_FAILED,
                arg = "over ${MAX_WAN_SEND_BYTES shr 30} GB, too large to send in one transfer",
            ))
            onSendFinished(peerHandle, false)
            return
        }
        val recipientOk = runCatching { provider.recipientFromQr(peerHandle.toByteArray(Charsets.UTF_8)) }.isSuccess
        if (!recipientOk) {
            onSendStatus(peerHandle, WanStatus(WanStatusKind.PREPARE_FAILED, arg = "not a valid device key"))
            onSendFinished(peerHandle, false)
            return
        }
        sending.add(peerHandle); onSendingChanged(sending.toSet())
        beginSend(peerHandle, SendJob(files, senderName, senderHandle))
    }

    /** True while a WAN send to [peer] is in progress. */
    fun isSending(peer: String): Boolean = sendJobs.containsKey(peer)

    /** Stop the send to [peer]. Main thread. The receiver sees the stream end and drops it. */
    fun cancelSend(peer: String) {
        finishSend(peer, success = false, cancelled = true)
    }

    private fun beginSend(peer: String, job: SendJob) {
        sendJobs[peer] = job
        onSendStatus(peer, WanStatus(WanStatusKind.CONNECTING))
        startPolling()
        if (!hooks.peerUsesMyInbox(peer)) announceInbox(peer)
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
        produce(peer, job,
            write = { wan.writeStream(it, peer) },
            buffered = { wan.streamSendBufferedBytes(peer) },
            finish = { wan.finishStream(peer) },
        )
    }

    private fun shipViaRelay(peer: String) {
        val job = sendJobs[peer] ?: return
        if (job.shipping) return
        val key = PonyPeerKeyProvider(myScalar, myHandle).pairKey(peer) ?: return
        job.shipping = true
        onSendStatus(peer, WanStatus(WanStatusKind.SENDING_RELAY))
        val ch = RelayStreamChannel(key, myHandle, peer, peerBaseUrl = peerRelayBase(peer))
        ch.onSendComplete = { main.post { finishSend(peer, true) } }
        job.relay = ch
        produce(peer, job,
            write = { ch.write(it) },
            buffered = { ch.sendBufferedBytes() },
            finish = { ch.finishSending() },
        )
    }

    /**
     * Encrypt [job]'s files into the chosen stream on a background thread, waiting on the stream's
     * acknowledgements so memory stays bounded however large the files are (4.0). Any failure (a
     * file that can't be read, a peer that stops acknowledging) ends the send as failed.
     */
    private fun produce(
        peer: String,
        job: SendJob,
        write: (ByteArray) -> Unit,
        buffered: () -> Long,
        finish: () -> Unit,
    ) {
        Thread({
            try {
                val recipient = provider.recipientFromQr(peer.toByteArray(Charsets.UTF_8))
                val total = job.files.sumOf { it.size }.coerceAtLeast(1)
                var sent = 0L
                var lastReport = 0L
                val counted: (ByteArray) -> Unit = { bytes ->
                    write(bytes)
                    sent += bytes.size
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 250) {
                        lastReport = now
                        val s = sent.coerceAtMost(total)
                        main.post { onSendProgress(peer, s, total) }
                    }
                }
                val out = BackpressureOutputStream(sink = counted, buffered = buffered, cancelled = { job.cancelled })
                // v1 monologue: one-directional, no reverse HELLO.
                Session.send(provider, listOf(recipient), job.senderName, job.senderHandle, job.files, out, 1, null)
                out.flush()
                if (!job.cancelled) finish()
            } catch (t: Throwable) {
                if (!job.cancelled) main.post { finishSend(peer, false) }
            }
        }, "relaypony-wan-send").apply { isDaemon = true; start() }
    }

    private fun finishSend(peer: String, success: Boolean, cancelled: Boolean = false) {
        val job = sendJobs[peer] ?: return
        if (job.finished) return
        job.finished = true
        job.cancelled = true
        job.fallback?.let { main.removeCallbacks(it) }
        job.relay?.stop()
        sendJobs.remove(peer)
        sending.remove(peer); onSendingChanged(sending.toSet())
        wan.close(peer)   // fresh session for the next transfer to this peer
        onSendStatus(peer, WanStatus(when {
            success -> WanStatusKind.SENT
            cancelled -> WanStatusKind.CANCELLED
            else -> WanStatusKind.SEND_FAILED
        }))
        onSendFinished(peer, success)
        stopPollingIfIdle()
    }

    private fun onConnected(peer: String) {
        sendJobs[peer]?.let { if (!it.shipping) shipDirect(peer) }
        recvJobs[peer]?.fallback?.let { main.removeCallbacks(it) }
    }

    // ---- Receive finalize ----

    private fun finishReceive(peer: String, spoolFile: File) {
        val rj = recvJobs[peer] ?: RecvJob().also { recvJobs[peer] = it }
        if (rj.finished) { spoolFile.delete(); return }
        rj.finished = true
        rj.fallback?.let { main.removeCallbacks(it) }
        recvJobs.remove(peer)
        decodeAndFile(peer, spoolFile)
    }

    /** A spool hit a ceiling mid-stream: end this receive, drop the session, report it. */
    private fun abortReceive(peer: String, t: Throwable) {
        recvJobs.remove(peer)?.let { rj ->
            rj.finished = true
            rj.fallback?.let { main.removeCallbacks(it) }
            rj.relay?.let { ch -> ch.stop(); ch.spool?.discard() }
        }
        wan.close(peer)
        onReceiveStatus(WanStatus(WanStatusKind.RECEIVE_FAILED, arg = t.message))
        stopPollingIfIdle()
    }

    private fun decodeAndFile(peer: String, spoolFile: File) {
        onReceiveStatus(WanStatus(WanStatusKind.RECEIVING))
        Thread {
            val written = ArrayList<ReceivedFileInfo>()
            try {
                val dir = saveDir().apply { mkdirs() }
                val result = spoolFile.inputStream().buffered(64 * 1024).use { input ->
                    Session.receive(
                        provider,
                        identity,
                        input,
                        FileSink { entry: FileEntry ->
                            val outFile = uniqueFile(dir, FileNames.sanitize(entry.name))
                            written.add(ReceivedFileInfo(outFile.name, entry.size, entry.mime, outFile.absolutePath))
                            outFile.outputStream()
                        },
                        null,
                        deviceName,
                        myHandle,
                        limits = limits(),
                    )
                }
                val batch = ReceivedBatch(peer, result.senderName, result.senderHandle, written)
                main.post {
                    onReceived(batch)
                    onReceiveStatus(WanStatus(WanStatusKind.RECEIVED, arg = result.senderName, count = written.size))
                    stopPollingIfIdle()
                }
            } catch (t: Throwable) {
                // Nothing from a failed or capped transfer reaches the inbox.
                written.forEach { runCatching { File(it.path).delete() } }
                main.post {
                    onReceiveStatus(WanStatus(WanStatusKind.RECEIVE_FAILED, arg = t.message))
                    stopPollingIfIdle()
                }
            } finally {
                runCatching { spoolFile.delete() }
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
                val h = hooks
                val box = h.myInbox()
                if (box != null) {
                    val inboxed = runCatching { h.myRelayClient()?.mboxPoll(box) }.getOrNull().orEmpty()
                    if (inboxed.isNotEmpty()) main.post { inboxed.forEach { handleSealed(it, viaInbox = true) } }
                }
                try { Thread.sleep(1500) } catch (e: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
    }

    /** Feed inbound blobs to the path, but only offers from *paired* peers; arm a per-peer
     *  relay-receive fallback the first time a paired peer reaches out. Runs on the main thread. */
    private fun ingest(blobs: List<String>) {
        // Sealed blobs first, so a 4.0 peer is marked before its plaintext duplicate is looked at.
        val (sealedLines, plainLines) = blobs.partition { it.startsWith(RelaySignaling.SEALED_LINE_PREFIX) }
        for (line in sealedLines) {
            val bytes = signaling.sealedPayload(line) ?: continue
            handleSealed(bytes, viaInbox = false)
        }
        for (line in plainLines) {
            val b = runCatching { SignalBlob.decode(line) }.getOrNull() ?: continue
            if (b.to != myHandle || !isPinned(b.from)) continue     // trust gate
            if (isSealedPeer(b.from)) continue                      // downgrade: this peer seals
            accept(b)
        }
    }

    /**
     * One sealed payload, from the handle queue or this device's inbox: open it, then dispatch on
     * its plaintext prefix (PROTOCOL_v3.md section 6.1). Main thread.
     */
    private fun handleSealed(bytes: ByteArray, viaInbox: Boolean) {
        val plain = runCatching { com.relaypony.session.pairing.PairMessages.open(provider, identity, bytes) }.getOrNull() ?: return
        if (!plain.startsWith("${SealedSignal.PREFIX}|")) {
            onInboxMessage(plain)
            return
        }
        // parse() authenticates `from` with the signal MAC, so the trust gate checks a proven
        // sender, not a claimed one.
        val b = runCatching { SealedSignal.parse(plain, myScalar, myHandle) }.getOrNull() ?: return
        if (!isPinned(b.from)) return                               // trust gate
        if (!isSealedPeer(b.from)) markSealedPeer(b.from)
        val h = hooks
        if (viaInbox) {
            h.markPeerUsesMyInbox(b.from)
        } else if (!h.peerUsesMyInbox(b.from) && !sendJobs.containsKey(b.from) && !recvJobs.containsKey(b.from)) {
            announceInbox(b.from)   // a 4.0 peer still reaching us by handle: tell it our inbox
        }
        accept(b)
    }

    /** The relay [peer] polls: its route's relay when known, else this device's own. */
    private fun peerRelayBase(peer: String): String =
        hooks.route(peer)?.let { RelayUrls.base(it.relay) } ?: RelayConfig.baseUrl

    /**
     * Post an already-sealed message (an unpair notice, say) to [peer] in the background: to its
     * inbox when a route is known, otherwise to its handle on this device's relay.
     */
    fun sendSealed(peer: String, sealed: ByteArray) {
        Thread { runCatching { signaling.sendRaw(peer, sealed) } }.apply { isDaemon = true; start() }
    }

    /** Send this device's inbox announcement to [peer] in the background (section 5.3). */
    private fun announceInbox(peer: String) {
        val sealed = hooks.announcement(peer) ?: return
        Thread { runCatching { signaling.sendRaw(peer, sealed) } }.apply { isDaemon = true; start() }
    }

    private fun accept(b: SignalBlob) {
        // First blob of a new incoming transfer (no live recv job): tear down any
        // session left over from a previous one so the offer builds a fresh session.
        if (!sendJobs.containsKey(b.from) && !recvJobs.containsKey(b.from)) {
            wan.close(b.from)
            ensureRecvJob(b.from)
        }
        runCatching { signaling.importSignal(b, wan) }
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
        val ch = RelayStreamChannel(key, myHandle, peer, peerBaseUrl = peerRelayBase(peer))
        val spool = runCatching { WanSpool.create(spoolDir(), limits()) }.getOrNull() ?: return
        ch.spool = spool
        ch.onFailed = { t -> spool.discard(); abortReceive(peer, t) }
        ch.onComplete = { _ ->
            main.post {
                val j = recvJobs[peer]
                if (j != null && !j.finished) {
                    j.finished = true
                    recvJobs.remove(peer)
                    val file = runCatching { spool.finish() }.getOrNull()
                    if (file != null) decodeAndFile(peer, file) else spool.discard()
                } else {
                    spool.discard()
                }
            }
        }
        rj.relay = ch
        ch.start()
    }

    private fun stopPollingIfIdle() {
        if (held || receiveActive || sendJobs.isNotEmpty() || recvJobs.isNotEmpty()) return
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
        sendJobs.values.forEach { it.cancelled = true; it.relay?.stop() }
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
