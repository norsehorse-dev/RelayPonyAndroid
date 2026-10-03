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
    /** The device answered but doesn't have this one paired (PROTOCOL_v3 section 9.3). */
    val refused: Boolean = false,
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
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false
        return runCatching { java.net.URI(t).host }.getOrNull()?.isNotEmpty() == true
    }

    /** Received .txt files at or under this size are checked for a text drop. Same as iOS. */
    const val MAX_SNIFF_BYTES = 16 * 1024

    /** What a received small .txt turned out to be. */
    sealed class Sniff {
        data class Text(val text: String) : Sniff()
        data class Link(val url: String) : Sniff()
    }

    fun isCandidate(name: String, size: Long): Boolean =
        name.lowercase(Locale.US).endsWith(".txt") && size in 1..MAX_SNIFF_BYTES.toLong()

    /** UTF-8 text gets Copy; a single http(s) link gets Open link too. Anything else is null. */
    fun sniff(bytes: ByteArray): Sniff? {
        if (bytes.isEmpty() || bytes.size > MAX_SNIFF_BYTES) return null
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            return null
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return if (isSingleUrl(trimmed)) Sniff.Link(trimmed) else Sniff.Text(text)
    }

    fun mime(text: String): String = if (isSingleUrl(text)) "text/uri-list" else "text/plain"

    fun toOutgoing(text: String, nowMs: Long = System.currentTimeMillis()): OutgoingFile {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return OutgoingFile(fileName(nowMs), mime(text), bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }
}
