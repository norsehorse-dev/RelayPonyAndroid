package com.relaypony.session.pairing

import com.relaypony.crypto.CryptoProvider
import com.relaypony.crypto.Identity

/**
 * Mutual QR pairing and inbox sharing, end to end except for the network (PROTOCOL_v3.md sections
 * 4 and 5.3). The app supplies a [Mailer] that delivers each pairing message by every path it has
 * (the relay mailbox, and a LAN PAIR frame when the peer is discovered with `pr=1`), feeds every
 * opened sealed message from this device's inbox to [onSealedPlain], and every PAIR frame its
 * listener accepts to [onLanPair]. A message that arrives by both paths is handled once: the QR
 * nonce and the scanner's pending request are both single use. Pins and routes land in the stores
 * it is given, so a headless test can run two of these against an in-memory relay.
 *
 * Not thread-safe: call from one thread (the app's main thread). [Mailer.send] may block, so the
 * app's implementation should hand the post to a background thread.
 */
class PairingService(
    private val provider: CryptoProvider,
    private val identity: Identity,
    private val myScalar: ByteArray,
    private val myHandle: String,
    private val myName: () -> String,
    private val myInbox: () -> String,
    /** Normalized relay this device polls, empty for the default. */
    private val myRelay: () -> String,
    private val trust: TrustStore,
    private val routes: PeerRouteStore,
    private val mailer: Mailer,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Delivers [payload] (a sealed pairing message, raw bytes) to the device [peer]: to [inbox] on
     * [relay] (the mailer base64s it), and over the LAN too when [peer] is discovered there.
     */
    fun interface Mailer {
        fun send(peer: String, relay: String, inbox: String, payload: ByteArray)
    }

    sealed class Event {
        /** Someone scanned our QR. Show [code] and ask the user to Confirm or Cancel. */
        data class Request(val pending: PairingFlow.Shower.Pending) : Event()
        /** We scanned their QR. Show [code] and "Waiting for <name> to confirm". */
        data class Waiting(val name: String, val code: String, val nonceB: ByteArray) : Event()
        /** Pairing finished and the peer is pinned with its route. */
        data class Paired(val handle: String, val name: String) : Event()
        /** The other side cancelled (or an ACK said no). */
        data class Declined(val handle: String) : Event()
        /** A one-way v1 pin (scanned a 3.x QR). */
        data class PairedOneWay(val handle: String, val name: String) : Event()
        /** A paired device told us its new inbox. */
        data class RouteUpdated(val handle: String) : Event()
        /** A paired device unpaired this one (section 4.7). Its pin and route are already gone. */
        data class Unpaired(val handle: String, val name: String) : Event()
    }

    var onEvent: (Event) -> Unit = {}

    private val shower = PairingFlow.Shower()
    private val scanner = PairingFlow.Scanner()

    /** A fresh QR for the pair sheet's "My code" tab. Retires the previous one. */
    fun showQr(): QrPayloadV2 = shower.newQr(myHandle, myName(), myInbox(), myRelay(), now())

    fun qrRemainingMs(): Long = shower.remainingMs(now())

    /** Handle a scanned QR. Throws IllegalArgumentException for anything that isn't a pairing QR. */
    fun onScanned(text: String) {
        when (val qr = ScannedQr.decode(text)) {
            is ScannedQr.V1 -> {
                Pairing.pinScanned(qr.payload, trust)
                onEvent(Event.PairedOneWay(qr.payload.recipientHandle, qr.payload.deviceName))
            }
            is ScannedQr.V2 -> {
                val (req, out) = scanner.request(qr.payload, myHandle, myName(), myInbox(), myRelay(), now())
                val sealed = PairMessages.seal(provider, qr.payload.recipientHandle, req.encode())
                mailer.send(qr.payload.recipientHandle, qr.payload.relay, qr.payload.inboxId, sealed)
                onEvent(Event.Waiting(qr.payload.deviceName, out.code, out.nonceB))
            }
        }
    }

    /** The user on the QR-showing side answered a [Event.Request]. */
    fun confirm(pending: PairingFlow.Shower.Pending, accept: Boolean) {
        val req = pending.request
        if (accept) {
            trust.pin(req.handle, req.name, now())
            routes.put(req.handle, PeerRoute(req.inboxId, req.relay))
        }
        val ack = PairMessages.encodeAck(PairMessages.PairAck(myHandle, req.handle, req.nonceB, accept), myScalar)
        mailer.send(req.handle, req.relay, req.inboxId, PairMessages.seal(provider, req.handle, ack))
        if (accept) onEvent(Event.Paired(req.handle, req.name))
    }

    /** The scanning side gave up waiting. */
    fun cancelWaiting(nonceB: ByteArray) = scanner.cancel(nonceB)

    /**
     * Feed one opened message from this device's inbox. Returns true if it was a pairing or inbox
     * message (consumed here), false if the caller should handle it (signaling).
     */
    fun onSealedPlain(plain: String): Boolean {
        when (PairMessages.kindOf(plain)) {
            PairMessages.Kind.PAIR_REQUEST -> {
                val req = runCatching { PairMessages.PairRequest.decode(plain) }.getOrNull() ?: return true
                val pending = shower.accept(req, myHandle, now()) ?: return true
                onEvent(Event.Request(pending))
            }
            PairMessages.Kind.PAIR_ACK -> handleAck(plain)
            PairMessages.Kind.INBOX -> {
                val a = runCatching { PairMessages.decodeInbox(plain, myScalar, myHandle) }.getOrNull() ?: return true
                if (!trust.isPinned(a.from)) return true
                routes.put(a.from, PeerRoute(a.inboxId, a.relay))
                onEvent(Event.RouteUpdated(a.from))
            }
            PairMessages.Kind.UNPAIR -> {
                val u = runCatching { PairMessages.decodeUnpair(plain, myScalar, myHandle) }.getOrNull() ?: return true
                val pinned = trust.get(u.from) ?: return true
                // Older than the pin: a captured notice replayed after the two paired again.
                if (u.atMs <= pinned.pinnedAtEpochMs) return true
                forget(u.from)
                onEvent(Event.Unpaired(u.from, pinned.name))
            }
            else -> return false
        }
        return true
    }

    private fun handleAck(plain: String) {
        // The ACK names A in field 1; the scanner only accepts it if it answers a QR we scanned
        // and its tag proves that handle.
        val claimedA = plain.split("|").getOrNull(1) ?: return
        val ack = runCatching { PairMessages.decodeAck(plain, myScalar, myHandle, claimedA) }.getOrNull() ?: return
        val out = scanner.complete(ack, now()) ?: return
        if (!ack.ok) {
            onEvent(Event.Declined(ack.handleA))
            return
        }
        trust.pin(out.qr.recipientHandle, out.qr.deviceName, now())
        routes.put(out.qr.recipientHandle, PeerRoute(out.qr.inboxId, out.qr.relay))
        onEvent(Event.Paired(out.qr.recipientHandle, out.qr.deviceName))
    }

    /**
     * Feed one PAIR frame accepted by the LAN listener. Only PAIR_REQ, PAIR_ACK and the unpair
     * notice are honoured on this path; anything else (including an inbox announcement) is dropped,
     * since the LAN listener accepts connections from anyone on the network. The unpair notice is
     * tagged under a pair key, so only a paired device can produce one.
     */
    fun onLanPair(sealed: ByteArray) {
        val plain = open(sealed) ?: return
        when (PairMessages.kindOf(plain)) {
            PairMessages.Kind.PAIR_REQUEST, PairMessages.Kind.PAIR_ACK, PairMessages.Kind.UNPAIR -> onSealedPlain(plain)
            else -> Unit
        }
    }

    /** The sealed RPI1 that tells [peer] this device's current inbox (section 5.3). */
    fun inboxAnnouncement(peer: String): ByteArray {
        val plain = PairMessages.encodeInbox(PairMessages.InboxAnnouncement(myHandle, peer, myInbox(), myRelay()), myScalar)
        return PairMessages.seal(provider, peer, plain)
    }

    /**
     * The sealed RPU1 telling [peer] this device unpaired it (section 4.7). Build it before
     * [forget], while the peer is still pinned.
     */
    fun unpairNotice(peer: String): ByteArray {
        val plain = PairMessages.encodeUnpair(PairMessages.Unpair(myHandle, peer, now()), myScalar)
        return PairMessages.seal(provider, peer, plain)
    }

    /** Drop [peer]'s pin and relay route on this device. */
    fun forget(peer: String) {
        trust.remove(peer)
        routes.remove(peer)
    }

    /** Open a sealed inbox payload to its plaintext line, or null if it isn't for us. */
    fun open(sealed: ByteArray): String? = runCatching { PairMessages.open(provider, identity, sealed) }.getOrNull()
}
