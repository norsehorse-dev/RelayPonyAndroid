package com.relaypony.session.pairing

/** Trust state of a discovered peer, used to drive the UI and gate sending. */
enum class PeerTrust {
    /** Handle is pinned: one-tap send is allowed. */
    PINNED,

    /** Handle has never been paired: a QR scan is required before sending. */
    UNKNOWN,
}

/**
 * Pairing decisions, all keyed on the recipient handle. This is what enforces the Phase 4
 * security property: a discovered peer is only one-tap sendable if the exact handle it advertises
 * is already pinned. Spoofing a pinned device's *name* over mDNS does nothing, because the lookup
 * is by handle, and the attacker's handle was never pinned.
 */
object Pairing {

    /** Classify a discovered peer by the handle it advertised. */
    fun classify(advertisedHandle: String, store: TrustStore): PeerTrust =
        if (store.isPinned(advertisedHandle)) PeerTrust.PINNED else PeerTrust.UNKNOWN

    /** Whether a one-tap send to the peer advertising [advertisedHandle] is allowed. */
    fun canSendOneTap(advertisedHandle: String, store: TrustStore): Boolean =
        store.isPinned(advertisedHandle)

    /** Pin a peer from a scanned QR payload. After this, that peer becomes one-tap sendable. */
    fun pinScanned(payload: QrPayload, store: TrustStore) {
        store.pin(payload.recipientHandle, payload.deviceName)
    }
}
