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
    /** 4.0: seals an outgoing blob ([SealedSignal.seal]). Null keeps the 3.x plaintext behaviour. */
    private val sealer: ((SignalBlob) -> ByteArray)? = null,
    /** True once [peerID] has been seen sending sealed blobs, so it no longer gets a plaintext copy. */
    private val sealedPeer: (String) -> Boolean = { false },
    /** 4.0: the peer's private inbox and relay, when known (PROTOCOL_v3.md section 5.1). */
    private val route: (String) -> com.relaypony.session.pairing.PeerRoute? = { null },
    /** Posts raw bytes to a mailbox on a relay (normalized relay, inbox id, payload). */
    private val mboxSender: ((String, String, ByteArray) -> Unit)? = null,
) : PonyDirectSignaling {

    companion object {
        /** Marks a polled line that carries a sealed blob: this prefix, then the relay's base64. */
        const val SEALED_LINE_PREFIX = "RPS2."
    }

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
        val blob = SignalBlob(kind, selfHandle, peerID, signal.sessionNonce, candidates)

        // A peer we have an inbox for is 4.0 by definition: sealed, to its inbox, and nothing else.
        val r = route(peerID)
        val send = mboxSender
        if (r != null && send != null) {
            val sealed = sealer?.let { runCatching { it(blob) }.getOrNull() }
            if (sealed != null && runCatching { send(r.relay, r.inboxId, sealed) }.isSuccess) {
                sentN.incrementAndGet()
                return
            }
        }

        // Sealed first, so a 4.0 receiver marks this device before it reaches the plaintext copy.
        val seal = sealer
        var sealedSent = false
        if (seal != null) {
            val sealed = runCatching { seal(blob) }.getOrNull()
            if (sealed != null) {
                val payload = Base64.encodeToString(sealed, Base64.NO_WRAP)
                sentN.incrementAndGet()
                post(JSONObject().put("op", "send").put("to", peerID).put("payload", payload).toString())
                sealedSent = true
            }
        }
        // Plaintext RPS1 only for peers not yet known to read sealed blobs (3.x, or a 4.0 peer we
        // haven't heard from since upgrading). A 3.x receiver silently skips the sealed copy.
        if (sealedSent && sealedPeer(peerID)) return
        val line = blob.encode()
        val payload = Base64.encodeToString(line.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sentN.incrementAndGet()
        post(JSONObject().put("op", "send").put("to", peerID).put("payload", payload).toString())
    }

    /**
     * Post an already-sealed message (an inbox announcement, say) to [peerID]: to its inbox when
     * known, otherwise to its handle on this relay. Blocking.
     */
    fun sendRaw(peerID: String, sealed: ByteArray) {
        val r = route(peerID)
        val send = mboxSender
        if (r != null && send != null && runCatching { send(r.relay, r.inboxId, sealed) }.isSuccess) return
        post(JSONObject().put("op", "send").put("to", peerID)
            .put("payload", Base64.encodeToString(sealed, Base64.NO_WRAP)).toString())
    }

    /** Poll the relay for blobs addressed to this device; returns the decoded RPS1 lines. */
    fun poll(): List<String> {
        val resp = post(JSONObject().put("op", "poll").put("for", selfHandle).toString()) ?: run { errN.incrementAndGet(); return emptyList() }
        return runCatching {
            val arr = JSONObject(resp).optJSONArray("blobs") ?: JSONArray()
            val out = (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val raw = arr.getString(i)
                    val bytes = Base64.decode(raw, Base64.NO_WRAP)
                    // A sealed blob is a binary age file; keep it as base64 behind a marker prefix
                    // instead of mangling it through a UTF-8 decode.
                    if (SealedSignal.isSealed(bytes)) SEALED_LINE_PREFIX + raw
                    else String(bytes, Charsets.UTF_8)
                }.getOrNull()
            }
            if (out.isNotEmpty()) recvN.addAndGet(out.size)
            out
        }.getOrDefault(emptyList())
    }

    /** The sealed age bytes behind a polled [SEALED_LINE_PREFIX] line, or null for a plaintext line. */
    fun sealedPayload(line: String): ByteArray? {
        if (!line.startsWith(SEALED_LINE_PREFIX)) return null
        return runCatching { Base64.decode(line.substring(SEALED_LINE_PREFIX.length), Base64.NO_WRAP) }.getOrNull()
    }

    /** Decode one polled blob and hand it to the path. Ignores a blob not addressed here. */
    fun importBlob(text: String, wan: PonyDirectWan) {
        importSignal(SignalBlob.decode(text), wan)
    }

    /** Hand an already-decoded (or unsealed and authenticated) blob to the path. */
    fun importSignal(b: SignalBlob, wan: PonyDirectWan) {
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
