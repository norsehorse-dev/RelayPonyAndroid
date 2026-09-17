package com.relaypony.session.wan

import android.util.Base64
import com.ponydirect.PonyDirectSignal
import com.ponydirect.PonyDirectSignaling
import com.ponydirect.PonyDirectWan
import com.relaypony.transport.SignalBlob
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** Relay endpoint config. Overridable for self-hosting. */
object RelayConfig {
    @Volatile
    var baseUrl: String = "https://relaypony.app"
}

/**
 * Relay-backed signaling: the twin of [PasteSignaling], but instead of the clipboard it ships each
 * [SignalBlob] through the RelayPony signaling relay (addressed by the peer's age handle) and polls
 * the relay for blobs addressed to this device. The relay only brokers the ~1 KB handshake; the file
 * transfer is peer-to-peer over the punched socket and never touches it. The blob is carried as an
 * opaque base64 payload, so the relay can be sealed later with no server change.
 */
class RelaySignaling(
    private val selfHandle: String,
    baseUrl: String = RelayConfig.baseUrl,
) : PonyDirectSignaling {

    private val endpoint = "${baseUrl.trimEnd('/')}/api/signal"

    private val sentN = AtomicInteger(0)
    private val recvN = AtomicInteger(0)
    private val errN = AtomicInteger(0)
    /** Live relay counters for on-device diagnostics. */
    val stats: String get() = "relay: sent=${sentN.get()} recv=${recvN.get()} httpErr=${errN.get()}"

    override fun sendSignal(signal: PonyDirectSignal, peerID: String) {
        val kind = when (signal.kind) {
            PonyDirectSignal.Kind.OFFER -> SignalBlob.Kind.OFFER
            PonyDirectSignal.Kind.ANSWER -> SignalBlob.Kind.ANSWER
            PonyDirectSignal.Kind.ICE -> SignalBlob.Kind.ICE
        }
        val candidates = if (signal.kind == PonyDirectSignal.Kind.ICE)
            listOfNotNull(signal.candidate) else signal.candidates
        val line = SignalBlob(kind, selfHandle, peerID, signal.sessionNonce, candidates).encode()
        val payload = Base64.encodeToString(line.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sentN.incrementAndGet()
        post(JSONObject().put("op", "send").put("to", peerID).put("payload", payload).toString())
    }

    /** Poll the relay for blobs addressed to this device; returns the decoded RPS1 lines. */
    fun poll(): List<String> {
        val resp = post(JSONObject().put("op", "poll").put("for", selfHandle).toString()) ?: run { errN.incrementAndGet(); return emptyList() }
        return runCatching {
            val arr = JSONObject(resp).optJSONArray("blobs") ?: JSONArray()
            val out = (0 until arr.length()).mapNotNull { i ->
                runCatching { String(Base64.decode(arr.getString(i), Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull()
            }
            if (out.isNotEmpty()) recvN.addAndGet(out.size)
            out
        }.getOrDefault(emptyList())
    }

    /** Decode one polled blob and hand it to the path. Ignores a blob not addressed here. */
    fun importBlob(text: String, wan: PonyDirectWan) {
        val b = SignalBlob.decode(text)
        require(b.to == selfHandle) { "blob addressed to ${b.to}, not this device" }
        val signal = when (b.kind) {
            SignalBlob.Kind.ICE ->
                PonyDirectSignal(PonyDirectSignal.Kind.ICE, candidate = b.candidates.firstOrNull())
            SignalBlob.Kind.OFFER ->
                PonyDirectSignal(PonyDirectSignal.Kind.OFFER, candidates = b.candidates, sessionNonce = b.sessionNonce)
            SignalBlob.Kind.ANSWER ->
                PonyDirectSignal(PonyDirectSignal.Kind.ANSWER, candidates = b.candidates, sessionNonce = b.sessionNonce)
        }
        wan.handleSignal(signal, b.from)
    }

    private fun post(body: String): String? = runCatching {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        text
    }.getOrNull()
}
