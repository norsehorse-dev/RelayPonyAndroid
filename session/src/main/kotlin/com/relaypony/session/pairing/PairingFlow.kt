package com.relaypony.session.pairing

import java.security.SecureRandom

/**
 * The state each side keeps during mutual QR pairing (PROTOCOL_v3.md sections 3 and 4). Pure logic
 * with the clock injected, so the freshness and single-use rules are unit-testable. The app owns
 * delivery (relay mailbox and LAN) and the UI around it.
 */
object PairingFlow {
    /** A QR nonce and a scanner's pending request are both good for 5 minutes. */
    const val VALIDITY_MS = 5 * 60 * 1000L

    /**
     * The QR shower (A). Only the most recently shown nonce is valid, for [VALIDITY_MS] and one
     * request.
     */
    class Shower(private val rng: SecureRandom = SecureRandom()) {
        private var nonce: ByteArray? = null
        private var issuedAt = 0L
        private var used = false

        /** A fresh QR for this device. Retires any earlier one. */
        fun newQr(handle: String, name: String, inboxId: String, relay: String, nowMs: Long): QrPayloadV2 {
            val n = QrPayloadV2.newNonce(rng)
            nonce = n
            issuedAt = nowMs
            used = false
            return QrPayloadV2(0x01, handle, name, n, inboxId, relay)
        }

        /** Milliseconds left on the current QR, 0 when there is none or it has expired or been used. */
        fun remainingMs(nowMs: Long): Long =
            if (nonce == null || used) 0 else (issuedAt + VALIDITY_MS - nowMs).coerceAtLeast(0)

        /**
         * Accept a PAIR_REQ, or return null if it doesn't carry the live nonce, the nonce has expired
         * or already been used, or the request names this device itself. A valid request spends the
         * nonce, so a second request (an attacker racing the real scanner) is ignored.
         */
        fun accept(req: PairMessages.PairRequest, myHandle: String, nowMs: Long): Pending? {
            val n = nonce ?: return null
            if (used || nowMs - issuedAt > VALIDITY_MS || nowMs < issuedAt) return null
            if (!req.nonceA.contentEquals(n)) return null
            if (req.handle == myHandle) return null
            used = true
            return Pending(req, SasV2.code(myHandle, req.handle, n, req.nonceB))
        }

        /** A request waiting for this device's user to compare codes. */
        data class Pending(val request: PairMessages.PairRequest, val code: String)
    }

    /** The scanner (B). Remembers what it asked so it can match the ACK. */
    class Scanner(private val rng: SecureRandom = SecureRandom()) {
        data class Outstanding(val qr: QrPayloadV2, val nonceB: ByteArray, val sentAt: Long, val code: String)

        private val outstanding = HashMap<String, Outstanding>()   // keyed by b64u(nonceB)

        /** Build the PAIR_REQ for a scanned QR, and the code this screen shows. */
        fun request(qr: QrPayloadV2, myHandle: String, myName: String, myInbox: String, myRelay: String, nowMs: Long):
            Pair<PairMessages.PairRequest, Outstanding> {
            require(qr.recipientHandle != myHandle) { "that's this device's own code" }
            val nb = QrPayloadV2.newNonce(rng)
            val req = PairMessages.PairRequest(myHandle, myName, myInbox, myRelay, qr.pairNonce, nb)
            val o = Outstanding(qr, nb, nowMs, SasV2.code(qr.recipientHandle, myHandle, qr.pairNonce, nb))
            outstanding[B64u.encode(nb)] = o
            return req to o
        }

        /**
         * Match a verified ACK to its request. Returns the request it answers (the caller pins
         * `qr.recipientHandle` with the QR's inbox and relay when `ack.ok`), or null if it answers
         * nothing pending or arrived too late. Either way the request is no longer pending.
         */
        fun complete(ack: PairMessages.PairAck, nowMs: Long): Outstanding? {
            val o = outstanding.remove(B64u.encode(ack.nonceB)) ?: return null
            if (o.qr.recipientHandle != ack.handleA) return null
            if (nowMs - o.sentAt > VALIDITY_MS) return null
            return o
        }

        fun cancel(nonceB: ByteArray) { outstanding.remove(B64u.encode(nonceB)) }
    }
}

/** Where to reach a paired device on the relay, learned during pairing or from an announcement. */
data class PeerRoute(val inboxId: String, val relay: String)

/** Per-peer relay routes, kept apart from [TrustStore] so the pinned model and backups don't change. */
interface PeerRouteStore {
    fun get(handle: String): PeerRoute?
    fun put(handle: String, route: PeerRoute)
    fun remove(handle: String)
}

class InMemoryPeerRouteStore : PeerRouteStore {
    private val routes = HashMap<String, PeerRoute>()
    override fun get(handle: String) = routes[handle]
    override fun put(handle: String, route: PeerRoute) { routes[handle] = route }
    override fun remove(handle: String) { routes.remove(handle) }
}
