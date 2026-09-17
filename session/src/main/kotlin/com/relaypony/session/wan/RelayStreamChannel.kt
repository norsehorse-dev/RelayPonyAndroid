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
) {
    var onProgress: (Int) -> Unit = {}
    var onComplete: (ByteArray) -> Unit = {}
    var onSendComplete: () -> Unit = {}

    private val endpoint = "${baseUrl.trimEnd('/')}/api/signal"
    private val main = Handler(Looper.getMainLooper())
    private val sessionHex: String
    private val outbuf = ArrayList<ByteArray>()          // touched only on the loop thread
    private val engine: PonyDirectStreamEngine
    private val recvBuffer = ByteArrayOutputStream()
    @Volatile private var running = false
    @Volatile private var pending: ByteArray? = null
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

    fun send(data: ByteArray) { pending = data; start() }

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    fun stop() { running = false; thread?.interrupt(); thread = null }

    private fun now(): Long = System.currentTimeMillis()

    private fun loop() {
        while (running) {
            pending?.let { engine.write(it); engine.finishSending(); pending = null }
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
            if (r.isNotEmpty()) { recvBuffer.write(r); val n = recvBuffer.size(); main.post { onProgress(n) } }
            if (!recvDone && engine.recvComplete()) { recvDone = true; val full = recvBuffer.toByteArray(); main.post { onComplete(full) } }
            if (!sendDone && engine.sendComplete()) { sendDone = true; main.post { onSendComplete() } }
            if (recvDone && sendDone) running = false
            try { Thread.sleep(60) } catch (e: InterruptedException) { break }
        }
    }

    private fun postData(batch: List<String>) {
        val arr = JSONArray(); batch.forEach { arr.put(it) }
        post(JSONObject().put("op", "data").put("to", peerHandle).put("session", sessionHex).put("chunks", arr).toString())
    }

    private fun fetchData(): List<String> {
        val resp = post(JSONObject().put("op", "fetch").put("for", selfHandle).put("session", sessionHex).toString())
            ?: return emptyList()
        return runCatching {
            val a = JSONObject(resp).optJSONArray("chunks") ?: JSONArray()
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun post(body: String): String? = runCatching {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
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
