package com.relaypony.pake

import com.relaypony.session.pairing.B64u
import com.relaypony.session.pairing.PakeDetails
import com.relaypony.session.wan.RelayClient
import uniffi.relaypony_pake_ffi.PakeFfiException
import uniffi.relaypony_pake_ffi.PakeSession
import uniffi.relaypony_pake_ffi.PakeSide
import uniffi.relaypony_pake_ffi.pakeGenerateCode
import uniffi.relaypony_pake_ffi.pakeMailboxId
import uniffi.relaypony_pake_ffi.pakeNameplateOf

/**
 * Word-code pairing over a relay (PROTOCOL_v3.md section 7). The crypto is RelayPonyPake's; this
 * class only moves its messages through the relay mailboxes and turns the result into the other
 * device's [PakeDetails]. Blocking: run it off the main thread. The caller pins the peer and
 * stores its route when [Result.Paired] comes back.
 *
 * Side A claims a nameplate and shows the code; side B types it. Both then run the same loop:
 * post our SPAKE2 message, read theirs, post our details sealed under the PAKE key, open theirs.
 */
class WordCodePairing(
    private val me: PakeDetails,
    private val clientFor: (String) -> RelayClient,
    private val pollIntervalMs: Long = 700,
) {
    /** A code ready to show, and what A needs to finish and release it. */
    data class Shown(val code: String, val nameplate: RelayClient.Nameplate, val relay: String)

    sealed class Result {
        data class Paired(val peer: PakeDetails) : Result()
        /** The codes differed. The attempt is spent; start over with a new code. */
        data object Mismatch : Result()
        /** Nobody answered in time ("Code not found or expired"). */
        data object Timeout : Result()
        data object Cancelled : Result()
        data class Failed(val reason: String, val kind: FailKind = FailKind.RELAY) : Result()
    }

    /** Why an attempt failed, so the app can show its own localized text. */
    enum class FailKind { NOT_A_CODE, OWN_CODE, UNEXPECTED, RELAY }

    /** Side A: claim a nameplate on this device's relay and make a code for it. */
    fun claim(): Shown {
        val relay = me.relay
        val n = clientFor(relay).claimNameplate()
        return Shown(pakeGenerateCode(n.number.toUInt()), n, relay)
    }

    /** Side A: wait for the other device and pair. Always releases the nameplate. */
    fun runAsA(shown: Shown, timeoutMs: Long = 120_000, cancelled: () -> Boolean = { false }): Result =
        try {
            run(shown.code, PakeSide.A, shown.nameplate.number, shown.relay, timeoutMs, cancelled)
        } finally {
            clientFor(shown.relay).releaseNameplate(shown.nameplate)
        }

    /** Side B: pair using a code read from the other device. [relay] is "" for the default relay. */
    fun runAsB(code: String, relay: String = "", timeoutMs: Long = 120_000, cancelled: () -> Boolean = { false }): Result {
        val n = pakeNameplateOf(code)?.toInt() ?: return Result.Failed("That isn't a pairing code.", FailKind.NOT_A_CODE)
        return run(code, PakeSide.B, n, relay, timeoutMs, cancelled)
    }

    private fun run(code: String, side: PakeSide, nameplate: Int, relay: String, timeoutMs: Long, cancelled: () -> Boolean): Result {
        val other = if (side == PakeSide.A) PakeSide.B else PakeSide.A
        val client = clientFor(relay)
        val mine = pakeMailboxId(nameplate.toUInt(), relay, side)
        val theirs = pakeMailboxId(nameplate.toUInt(), relay, other)
        val session = try {
            PakeSession(code, side)
        } catch (e: PakeFfiException) {
            return Result.Failed("That isn't a pairing code.", FailKind.NOT_A_CODE)
        }
        session.use { s ->
            try {
                client.mboxSend(theirs, line("pake", s.outbound()))
            } catch (e: Exception) {
                return Result.Failed(e.message ?: "relay unreachable")
            }
            var finished = false
            var pendingData: ByteArray? = null
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (cancelled()) return Result.Cancelled
                for (raw in client.mboxPoll(mine)) {
                    val text = String(raw, Charsets.UTF_8)
                    when {
                        text.startsWith("RPK1|pake|") && !finished -> {
                            val peerMsg = runCatching { B64u.decode(text.removePrefix("RPK1|pake|")) }.getOrNull() ?: continue
                            try {
                                s.finish(peerMsg)
                            } catch (e: PakeFfiException) {
                                return Result.Failed("The other device sent something unexpected.", FailKind.UNEXPECTED)
                            }
                            finished = true
                            try {
                                client.mboxSend(theirs, line("data", s.sealData(me.encode().toByteArray(Charsets.UTF_8))))
                            } catch (e: Exception) {
                                return Result.Failed(e.message ?: "relay unreachable")
                            }
                        }
                        text.startsWith("RPK1|data|") ->
                            pendingData = runCatching { B64u.decode(text.removePrefix("RPK1|data|")) }.getOrNull()
                    }
                }
                val data = pendingData
                if (finished && data != null) {
                    val plain = try {
                        s.openData(data)
                    } catch (e: PakeFfiException) {
                        return Result.Mismatch
                    }
                    val peer = runCatching { PakeDetails.decode(String(plain, Charsets.UTF_8)) }.getOrNull()
                        ?: return Result.Failed("The other device sent something unexpected.", FailKind.UNEXPECTED)
                    if (peer.handle == me.handle) return Result.Failed("That's this device's own code.", FailKind.OWN_CODE)
                    return Result.Paired(peer)
                }
                try { Thread.sleep(pollIntervalMs) } catch (e: InterruptedException) { return Result.Cancelled }
            }
            return Result.Timeout
        }
    }

    private fun line(kind: String, bytes: ByteArray): ByteArray =
        "RPK1|$kind|${B64u.encode(bytes)}".toByteArray(Charsets.UTF_8)
}
