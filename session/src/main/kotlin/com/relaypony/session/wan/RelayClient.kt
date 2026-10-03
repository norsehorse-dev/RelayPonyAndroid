package com.relaypony.session.wan

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Relay 2.0 client ops (PROTOCOL_v3.md section 8): feature detection, private mailboxes and
 * word-code nameplates. Blocking; call off the main thread. Signaling send/poll by handle stays in
 * [RelaySignaling].
 *
 * Every payload crosses the relay as standard base64 of the message bytes, so callers pass and get
 * raw bytes here.
 */
class RelayClient(baseUrl: String = RelayConfig.baseUrl) {
    private val endpoint = "${baseUrl.trimEnd('/')}/api/signal"

    data class Info(val version: String, val features: Set<String>) {
        val hasMailboxes get() = "mbox" in features
        val hasNameplates get() = "nameplate" in features
    }

    data class Nameplate(val number: Int, val token: String, val ttlSeconds: Int)

    class RelayException(val code: String) : Exception("relay error: $code")

    /** Relay version and features, or null for a 1.x relay (no `info` op) or no answer. */
    fun info(): Info? {
        val o = post(JSONObject().put("op", "info")) ?: return null
        if (!o.optBoolean("ok")) return null
        val arr = o.optJSONArray("features") ?: JSONArray()
        return Info(o.optString("version"), (0 until arr.length()).map { arr.getString(it) }.toSet())
    }

    fun mboxSend(mailbox: String, payload: ByteArray) {
        val o = post(
            JSONObject().put("op", "mbox.send").put("to", mailbox)
                .put("payload", Base64.encodeToString(payload, Base64.NO_WRAP)),
        ) ?: throw RelayException("unreachable")
        if (!o.optBoolean("ok")) throw RelayException(o.optString("error", "unknown"))
    }

    /** Everything queued for [mailbox]; the relay deletes what it returns. Empty on any failure. */
    fun mboxPoll(mailbox: String): List<ByteArray> {
        val o = post(JSONObject().put("op", "mbox.poll").put("for", mailbox)) ?: return emptyList()
        val arr = o.optJSONArray("blobs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { runCatching { Base64.decode(arr.getString(it), Base64.NO_WRAP) }.getOrNull() }
    }

    fun claimNameplate(): Nameplate {
        val o = post(JSONObject().put("op", "nameplate.claim")) ?: throw RelayException("unreachable")
        if (!o.optBoolean("ok")) throw RelayException(o.optString("error", "unknown"))
        return Nameplate(o.getInt("nameplate"), o.getString("token"), o.optInt("ttl", 600))
    }

    /** Best effort; a nameplate that isn't released simply expires. */
    fun releaseNameplate(n: Nameplate) {
        runCatching { post(JSONObject().put("op", "nameplate.release").put("nameplate", n.number).put("token", n.token)) }
    }

    private fun post(body: JSONObject): JSONObject? = runCatching {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        text?.let { JSONObject(it) }
    }.getOrNull()
}
