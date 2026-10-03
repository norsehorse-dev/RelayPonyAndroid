package com.relaypony.session

import com.relaypony.crypto.PeerKey
import com.relaypony.transport.WireProtocol
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The HELLO sender tag (PROTOCOL_v3.md section 9.1):
 *
 *   tail = "RPH1" || u64be(timeMs) || HMAC(K("relaypony/hello/v1"), "RPH1" || u64be(timeMs) || body)
 *
 * It proves the HELLO came from the holder of the handle it names, which only a paired device can
 * show to this one, since the key needs both devices' handles and one of their secrets.
 */
object HelloAuth {
    const val INFO = "relaypony/hello/v1"

    /** How far the sender's clock may be from ours. */
    const val MAX_SKEW_MS = 10 * 60 * 1000L

    private fun u64(v: Long) = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }

    private fun mac(key: ByteArray, timeMs: Long, body: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        m.update(WireProtocol.HELLO_AUTH_MAGIC)
        m.update(u64(timeMs))
        m.update(body)
        return m.doFinal()
    }

    /** The tail a sender with [myScalar]/[myHandle] appends to [body] for [peerHandle]. */
    fun tail(myScalar: ByteArray, myHandle: String, peerHandle: String, body: ByteArray, timeMs: Long): ByteArray {
        val key = PeerKey.deriveFromHandles(myScalar, myHandle, peerHandle, INFO)
        return WireProtocol.HELLO_AUTH_MAGIC + u64(timeMs) + mac(key, timeMs, body)
    }

    /** A sender-side tail builder for [WireProtocol.writeHelloV2]. */
    fun signer(myScalar: ByteArray, myHandle: String, peerHandle: String, now: () -> Long = System::currentTimeMillis): (ByteArray) -> ByteArray =
        { body -> tail(myScalar, myHandle, peerHandle, body, now()) }

    /** True if [auth] proves [claimedHandle] sent the HELLO to this device, with a fresh time. */
    fun verify(myScalar: ByteArray, myHandle: String, claimedHandle: String, auth: WireProtocol.HelloAuthTail, nowMs: Long): Boolean {
        if (kotlin.math.abs(nowMs - auth.timeMs) > MAX_SKEW_MS) return false
        val key = runCatching { PeerKey.deriveFromHandles(myScalar, myHandle, claimedHandle, INFO) }.getOrNull() ?: return false
        return MessageDigest.isEqual(mac(key, auth.timeMs, auth.body), auth.tag)
    }
}

/** The receiver refused the transfer (section 9.3), or this side refused one. */
class RefusedException(
    val reason: String,
    val senderName: String = "",
    val senderHandle: String = "",
) : Exception("refused: $reason")
