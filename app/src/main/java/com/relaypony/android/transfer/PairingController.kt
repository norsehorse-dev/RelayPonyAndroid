package com.relaypony.android.transfer

import android.os.Handler
import androidx.compose.runtime.mutableStateOf
import com.relaypony.android.R
import com.relaypony.pake.WordCodePairing
import com.relaypony.session.pairing.PairingFlow
import com.relaypony.session.pairing.PairingService
import com.relaypony.session.pairing.PakeDetails
import com.relaypony.session.pairing.QrPayloadV2
import com.relaypony.session.wan.RelayUrls
import kotlin.concurrent.thread

/**
 * Everything behind the pair sheet (PROTOCOL_v3.md sections 3, 4 and 7): the live QR, what a scan
 * turned into, the code comparison on both sides, word codes, and pair-and-send. The first piece
 * split out of [TransferController]; the network side stays in [PairingService] and
 * [WordCodePairing]. Main thread only, like [PairingService].
 */
class PairingController(
    private val service: PairingService,
    private val newWordCode: () -> WordCodePairing,
    /** Whether this device's relay has mailboxes and nameplates (relay 2.0), null if unknown. */
    private val relayHasMailboxes: () -> Boolean?,
    private val myHandle: String,
    /** Keep the LAN listener, discovery and inbox polling up (true) or put them back (false). */
    private val setListening: (Boolean) -> Unit,
    /** A device was just pinned, by either path. */
    private val onPinned: (handle: String, name: String) -> Unit,
    /** Pin a device that a word code paired, with its relay route. */
    private val pinWordCodePeer: (PakeDetails) -> Unit,
    /** A paired device unpaired this one; its pin and route are already gone. */
    private val onUnpinned: (handle: String) -> Unit,
    private val main: Handler,
) {
    enum class Tab { QR, SCAN, WORD }

    /** Whether the pair sheet is up, and on which tab. */
    val sheetOpen = mutableStateOf(false)
    val tab = mutableStateOf(Tab.QR)

    /** Starts the transfer to a device the moment it is pinned (section 4.6). Set by the app. */
    var onPairAndSend: (handle: String) -> Unit = {}

    /** Set when the sheet was opened from Send with files staged. */
    private var sendAfterPair = false

    fun open(tab: Tab = Tab.QR, sendAfter: Boolean = false) {
        this.tab.value = tab
        sendAfterPair = sendAfter
        sheetOpen.value = true
    }

    fun close() {
        sheetOpen.value = false
        if (word.value is WordCode.Working || word.value is WordCode.Showing) cancelWordCode()
        if (word.value is WordCode.Failed) word.value = null
    }

    // ---- Listening while pairing ----

    private var surfaces = 0
    private var listening = false
    private val stopListening = Runnable {
        if (!wantsListening()) {
            listening = false
            setListening(false)
        }
    }

    /** The pair sheet appeared (true) or went away (false). */
    fun surfaceVisible(visible: Boolean) {
        surfaces = (surfaces + if (visible) 1 else -1).coerceAtLeast(0)
        updateListening()
    }

    private fun wantsListening(): Boolean =
        surfaces > 0 || prompt.value is Prompt.Confirm || prompt.value is Prompt.Waiting

    /**
     * Listening stays up while the sheet is open or a comparison is pending, and for a short while
     * after, so a pair-and-send from the other device still finds this one listening.
     */
    private fun updateListening() {
        if (wantsListening()) {
            main.removeCallbacks(stopListening)
            if (!listening) {
                listening = true
                setListening(true)
            }
        } else if (listening) {
            main.removeCallbacks(stopListening)
            main.postDelayed(stopListening, LINGER_MS)
        }
    }

    // ---- QR code (section 3) ----

    /** The QR currently on screen. Its nonce is good for 5 minutes and one use. */
    val qr = mutableStateOf<QrPayloadV2?>(null)

    fun qrRemainingMs(): Long = service.qrRemainingMs()

    /** A fresh QR. Retires the one on screen. */
    fun refreshQr() {
        qr.value = service.showQr()
    }

    /** A fresh QR if there is none or it has expired or been used, unless a comparison is up. */
    fun ensureQr() {
        if (prompt.value is Prompt.Confirm) return
        if (qr.value == null || service.qrRemainingMs() == 0L) refreshQr()
    }

    // ---- Prompts (section 4.3) ----

    sealed class Prompt {
        /** Someone scanned this device's QR: compare and Confirm or Cancel. */
        data class Confirm(val pending: PairingFlow.Shower.Pending) : Prompt()
        /** This device scanned a QR and waits for the other to confirm. */
        class Waiting(val name: String, val code: String, val nonceB: ByteArray) : Prompt()
        data class Paired(val name: String, val oneWay: Boolean, val sending: Boolean) : Prompt()
        data class Declined(val name: String) : Prompt()
        data class NoAnswer(val name: String) : Prompt()
        data class Problem(val text: UiText) : Prompt()
        /** The other device unpaired this one (PROTOCOL_v3.md section 4.7). */
        data class UnpairedBy(val name: String) : Prompt()
    }

    val prompt = mutableStateOf<Prompt?>(null)

    private fun setPrompt(p: Prompt?) {
        prompt.value = p
        updateListening()
    }

    /** A QR the camera read. */
    fun onScanned(text: String) {
        if (prompt.value != null) return
        try {
            service.onScanned(text)
        } catch (e: IllegalArgumentException) {
            val own = text.contains(myHandle)
            setPrompt(Prompt.Problem(UiText(if (own) R.string.pair_own_code else R.string.pair_not_a_code)))
        }
    }

    fun onEvent(ev: PairingService.Event) {
        when (ev) {
            is PairingService.Event.Request -> setPrompt(Prompt.Confirm(ev.pending))
            is PairingService.Event.Waiting -> {
                setPrompt(Prompt.Waiting(ev.name, ev.code, ev.nonceB))
                val nonceB = ev.nonceB
                main.postDelayed({
                    val p = prompt.value
                    if (p is Prompt.Waiting && p.nonceB.contentEquals(nonceB)) {
                        service.cancelWaiting(nonceB)
                        setPrompt(Prompt.NoAnswer(p.name))
                    }
                }, PairingFlow.VALIDITY_MS)
            }
            is PairingService.Event.Paired -> pinned(ev.handle, ev.name, oneWay = false)
            is PairingService.Event.PairedOneWay -> pinned(ev.handle, ev.name, oneWay = true)
            is PairingService.Event.Declined -> setPrompt(Prompt.Declined((prompt.value as? Prompt.Waiting)?.name ?: ""))
            is PairingService.Event.RouteUpdated -> Unit
            is PairingService.Event.Unpaired -> {
                onUnpinned(ev.handle)
                // Don't cover a code comparison that is in progress with something else.
                if (prompt.value == null) setPrompt(Prompt.UnpairedBy(ev.name))
            }
        }
    }

    /** The user answered a [Prompt.Confirm]. */
    fun answer(accept: Boolean) {
        val p = prompt.value as? Prompt.Confirm ?: return
        setPrompt(null)
        service.confirm(p.pending, accept)
    }

    /** The user gave up waiting for the other device. */
    fun cancelWaiting() {
        val p = prompt.value as? Prompt.Waiting ?: return
        service.cancelWaiting(p.nonceB)
        setPrompt(null)
    }

    fun dismissPrompt() {
        when (prompt.value) {
            is Prompt.Confirm -> answer(false)
            is Prompt.Waiting -> cancelWaiting()
            else -> setPrompt(null)
        }
    }

    private fun pinned(handle: String, name: String, oneWay: Boolean) {
        onPinned(handle, name)
        val send = sendAfterPair
        sendAfterPair = false
        sheetOpen.value = false
        setPrompt(Prompt.Paired(name, oneWay, send))
        if (send) onPairAndSend(handle)
    }

    // ---- Word codes (section 7) ----

    sealed class WordCode {
        data object Working : WordCode()
        data class Showing(val code: String, val relay: String) : WordCode()
        data class Failed(val text: UiText) : WordCode()
    }

    val word = mutableStateOf<WordCode?>(null)

    @Volatile private var wordCancelled = false
    /** Bumped per attempt, so a late result from an abandoned attempt is ignored. */
    @Volatile private var wordGen = 0

    /** Side A: get a code from this device's relay, show it, and wait for the other device. */
    fun showWordCode() {
        if (relayHasMailboxes() == false) {
            word.value = WordCode.Failed(UiText(R.string.word_relay_old))
            return
        }
        val gen = ++wordGen
        wordCancelled = false
        word.value = WordCode.Working
        thread(name = "relaypony-wordcode") {
            val wc = newWordCode()
            val shown = runCatching { wc.claim() }.getOrElse {
                main.post { if (gen == wordGen) word.value = WordCode.Failed(UiText(R.string.word_relay_unreachable)) }
                return@thread
            }
            main.post { if (gen == wordGen) word.value = WordCode.Showing(shown.code, shown.relay) }
            val result = runCatching { wc.runAsA(shown, cancelled = { wordCancelled || gen != wordGen }) }
                .getOrElse { WordCodePairing.Result.Failed(it.message ?: "relay unreachable") }
            main.post { finishWordCode(gen, result) }
        }
    }

    /** Side B: pair with a code typed (or auto-typed) from the other device. */
    fun enterWordCode(code: String, relay: String) {
        val gen = ++wordGen
        wordCancelled = false
        word.value = WordCode.Working
        val normalizedRelay = RelayUrls.normalize(relay)
        thread(name = "relaypony-wordcode") {
            val result = runCatching {
                newWordCode().runAsB(code.trim(), normalizedRelay, cancelled = { wordCancelled || gen != wordGen })
            }.getOrElse { WordCodePairing.Result.Failed(it.message ?: "relay unreachable") }
            main.post { finishWordCode(gen, result) }
        }
    }

    /** Stop the current attempt. Side A releases its nameplate on the way out. */
    fun cancelWordCode() {
        wordCancelled = true
        wordGen++
        word.value = null
    }

    /** Back to the start of the Word code tab after a failure. */
    fun resetWordCode() {
        word.value = null
    }

    private fun finishWordCode(gen: Int, result: WordCodePairing.Result) {
        if (gen != wordGen) return
        when (result) {
            is WordCodePairing.Result.Paired -> {
                word.value = null
                pinWordCodePeer(result.peer)
                pinned(result.peer.handle, result.peer.name, oneWay = false)
            }
            WordCodePairing.Result.Mismatch -> word.value = WordCode.Failed(UiText(R.string.word_mismatch))
            WordCodePairing.Result.Timeout -> word.value = WordCode.Failed(UiText(R.string.word_timeout))
            WordCodePairing.Result.Cancelled -> word.value = null
            is WordCodePairing.Result.Failed -> word.value = WordCode.Failed(
                UiText(
                    when (result.kind) {
                        WordCodePairing.FailKind.NOT_A_CODE -> R.string.word_not_a_code
                        WordCodePairing.FailKind.OWN_CODE -> R.string.word_own_code
                        WordCodePairing.FailKind.UNEXPECTED -> R.string.word_unexpected
                        WordCodePairing.FailKind.RELAY -> R.string.word_relay_unreachable
                    },
                ),
            )
        }
    }

    private companion object {
        /** How long listening stays up after the sheet and every prompt are gone. */
        const val LINGER_MS = 30_000L
    }
}
