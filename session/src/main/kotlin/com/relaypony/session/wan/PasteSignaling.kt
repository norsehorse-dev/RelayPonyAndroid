package com.relaypony.session.wan

import com.ponydirect.PonyDirectSignal
import com.ponydirect.PonyDirectSignaling
import com.ponydirect.PonyDirectWan
import com.relaypony.transport.SignalBlob

/**
 * The serverless signaling channel for the WAN spike. Outgoing PonyDirect signals are turned into a
 * copyable [SignalBlob] line handed to [onOutgoing] (the UI shows it; the user sends it over any
 * channel they already have); a pasted blob is fed back in via [importBlob]. No rendezvous server.
 */
class PasteSignaling(
    private val selfHandle: String,
    private val onOutgoing: (String) -> Unit,
) : PonyDirectSignaling {

    override fun sendSignal(signal: PonyDirectSignal, peerID: String) {
        val kind = when (signal.kind) {
            PonyDirectSignal.Kind.OFFER -> SignalBlob.Kind.OFFER
            PonyDirectSignal.Kind.ANSWER -> SignalBlob.Kind.ANSWER
            PonyDirectSignal.Kind.ICE -> SignalBlob.Kind.ICE
        }
        val candidates = if (signal.kind == PonyDirectSignal.Kind.ICE)
            listOfNotNull(signal.candidate) else signal.candidates
        onOutgoing(SignalBlob(kind, selfHandle, peerID, signal.sessionNonce, candidates).encode())
    }

    /** Decode a pasted blob and feed it to the WAN path. Ignores a blob not addressed to this device. */
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
}
