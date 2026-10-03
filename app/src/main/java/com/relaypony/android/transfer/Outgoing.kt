package com.relaypony.android.transfer

import com.relaypony.session.OutgoingFile
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How a send to one device is travelling (plan section 4.4). */
enum class SendRoute {
    /** Discovered on this network: straight over the LAN. */
    NEARBY,
    /** Over the internet, still connecting. */
    INTERNET,
    /** Over the internet, hole-punched peer to peer. */
    INTERNET_DIRECT,
    /** Over the internet, forwarded through the relay. */
    INTERNET_RELAY,
}

enum class LegState { CONNECTING, SENDING, SENT, FAILED, CANCELLED }

/** One device's part of a send. */
data class SendLeg(
    val handle: String,
    val name: String,
    val route: SendRoute,
    val state: LegState,
    /** 0..1, or null while it can't be known yet. */
    val progress: Float? = null,
    val error: String? = null,
) {
    val active: Boolean get() = state == LegState.CONNECTING || state == LegState.SENDING
}

/** What the transfer screen shows: the files, and each device they are going to. */
data class OutgoingBatch(
    val id: Long,
    val fileNames: List<String>,
    val totalBytes: Long,
    val legs: List<SendLeg>,
) {
    val active: Boolean get() = legs.any { it.active }
    val allSent: Boolean get() = legs.all { it.state == LegState.SENT }
}

/** A paired device as the Send-to sheet lists it. */
data class SendTarget(val handle: String, val name: String, val nearby: Boolean)

/**
 * Text drops in the iOS ClipDrop format (plan section 5): a UTF-8 file named
 * `clip-yyyyMMdd-HHmmss.txt`, `text/uri-list` when it is a single http(s) URL and `text/plain`
 * otherwise. Any receiver, including a 3.x one, gets an ordinary .txt file.
 */
object ClipText {
    fun fileName(nowMs: Long = System.currentTimeMillis()): String =
        "clip-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs)) + ".txt"

    fun isSingleUrl(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return false
        val lower = t.lowercase(Locale.US)
        return (lower.startsWith("https://") && t.length > 8) || (lower.startsWith("http://") && t.length > 7)
    }

    fun mime(text: String): String = if (isSingleUrl(text)) "text/uri-list" else "text/plain"

    fun toOutgoing(text: String, nowMs: Long = System.currentTimeMillis()): OutgoingFile {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return OutgoingFile(fileName(nowMs), mime(text), bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }
}
