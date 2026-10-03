package com.relaypony.session.wan

import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.ponydirect.PonyDirectStreamEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Relay-forward fallback for the reliable stream: used ONLY when a direct hole-punch is impossible
 * (typically one peer on carrier-grade NAT). Drives a PonyDirectStreamEngine — the same ARQ the
 * direct path uses — but ships its datagrams over HTTPS through the RelayPony relay instead of the
 * punched UDP socket. Same end-to-end-authenticated frames, so the relay only sees ciphertext.
 *
 * No signaling exchange: both peers derive the same per-pair session nonce from their sorted handles
 * and both already hold the shared pair key. Twin of the Swift RelayStreamChannel.
 */
class RelayStreamChannel(
    pairKey: ByteArray,
    private val selfHandle: String,
    private val peerHandle: String,
    baseUrl: String = RelayConfig.baseUrl,
    /** 4.0: the relay the PEER polls. Datagrams go there; this side fetches from [baseUrl]. Lets two
     *  devices on different relays still use the fallback. Defaults to the same relay (3.x). */
    peerBaseUrl: String = baseUrl,
) {
    var onProgress: (Int) -> Unit = {}
    var onComplete: (ByteArray) -> Unit = {}
    var onSendComplete: () -> Unit = {}
    /** The receive was cut off because the spool hit a ceiling. Posted to main. */
    var onFailed: (Throwable) -> Unit = {}

    /** When set, received bytes go to this disk spool instead of memory (4.0), and [onComplete]
     *  is called with an empty array; the caller reads the spool. */
    @Volatile var spool: WanSpool? = null

    private val endpoint = "${baseUrl.trimEnd('/')}/api/signal"
    private val peerEndpoint = "${peerBaseUrl.trimEnd('/')}/api/signal"
    private val main = Handler(Looper.getMainLooper())
    private val sessionHex: String
    private val outbuf = ArrayList<ByteArray>()          // touched only on the loop thread
    private val engine: PonyDirectStreamEngine
    private val recvBuffer = ByteArrayOutputStream()
    @Volatile private var running = false
    // Outbound bytes from a streaming writer (4.0), handed to the engine on the loop thread.
    private val outQueue = ConcurrentLinkedQueue<ByteArray>()
    private val queuedBytes = AtomicLong()
    @Volatile private var finishRequested = false
    private var finCalled = false
    @Volatile private var engineHeld = 0L
    private var recvDone = false
    private var sendDone = false
    private var thread: Thread? = null

    init {
        val lo = if (selfHandle <= peerHandle) selfHandle else peerHandle
        val hi = if (selfHandle <= peerHandle) peerHandle else selfHandle
        val md = MessageDigest.getInstance("SHA-256")
        md.update(lo.toByteArray(Charsets.UTF_8)); md.update(0); md.update(hi.toByteArray(Charsets.UTF_8))
        md.update("relaypony/relay-stream/v1".toByteArray(Charsets.UTF_8))
        val nonce = md.digest().copyOfRange(0, 16)
        sessionHex = nonce.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        engine = PonyDirectStreamEngine(pairKey, nonce) { d -> outbuf.add(d) }
    }

    /** Send [data] as the whole stream (3.x style). */
    fun send(data: ByteArray) { write(data); finishSending() }

    /** Append to the outbound stream. Returns at once; see [sendBufferedBytes] for backpressure. */
    fun write(bytes: ByteArray) {
        queuedBytes.addAndGet(bytes.size.toLong())
        outQueue.add(bytes)
        start()
    }

    /** End the outbound stream once everything written so far is sent. */
    fun finishSending() { finishRequested = true; start() }

    /** Outbound bytes not yet acknowledged, including ones not yet handed to the engine. */
    fun sendBufferedBytes(): Long = queuedBytes.get() + engineHeld

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    fun stop() { running = false; thread?.interrupt(); thread = null }

    private fun now(): Long = System.currentTimeMillis()

    private fun loop() {
        while (running) {
            while (true) {
                val b = outQueue.poll() ?: break
                engine.write(b)
                queuedBytes.addAndGet(-b.size.toLong())
            }
            if (finishRequested && !finCalled && outQueue.isEmpty()) { engine.finishSending(); finCalled = true }
            engine.tick(now())
            if (outbuf.isNotEmpty()) {
                val all = outbuf.map { Base64.encodeToString(it, Base64.NO_WRAP) }
                outbuf.clear()
                var i = 0
                while (i < all.size) {
                    postData(all.subList(i, minOf(i + 64, all.size)))
                    i += 64
                }
            }
            for (c in fetchData()) {
                runCatching { Base64.decode(c, Base64.NO_WRAP) }.getOrNull()?.let { engine.onWireDatagram(now(), it) }
            }
            val r = engine.read(1 shl 20)
            if (r.isNotEmpty()) {
                val sp = spool
                if (sp != null) {
                    try {
                        sp.write(r)
                    } catch (t: Throwable) {
                        running = false
                        main.post { onFailed(t) }
                        break
                    }
                    val n = sp.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    main.post { onProgress(n) }
                } else {
                    recvBuffer.write(r); val n = recvBuffer.size(); main.post { onProgress(n) }
                }
            }
            if (!recvDone && engine.recvComplete()) { recvDone = true; val full = recvBuffer.toByteArray(); main.post { onComplete(full) } }
            engineHeld = engine.sendBufferedBytes()
            if (!sendDone && engine.sendComplete()) { sendDone = true; main.post { onSendComplete() } }
            if (recvDone && sendDone) running = false
            try { Thread.sleep(60) } catch (e: InterruptedException) { break }
        }
    }

    private fun postData(batch: List<String>) {
        val arr = JSONArray(); batch.forEach { arr.put(it) }
        post(JSONObject().put("op", "data").put("to", peerHandle).put("session", sessionHex).put("chunks", arr).toString(), peerEndpoint)
    }

    private fun fetchData(): List<String> {
        val resp = post(JSONObject().put("op", "fetch").put("for", selfHandle).put("session", sessionHex).toString())
            ?: return emptyList()
        return runCatching {
            val a = JSONObject(resp).optJSONArray("chunks") ?: JSONArray()
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun post(body: String, url: String = endpoint): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 8000; readTimeout = 12000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        text
    }.getOrNull()
}
