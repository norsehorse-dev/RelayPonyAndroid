package com.relaypony.session.wan

import com.ponydirect.PonyDirectKeyProvider
import com.relaypony.crypto.PeerKey

/**
 * Feeds PonyDirect the per-peer key derived from RelayPony's own pairing material: this device's age
 * X25519 secret and the peer's pinned age1 handle (the peerID PonyDirect passes here). No new secret
 * and no re-pairing; the derivation and its cross-platform vector live in [PeerKey].
 */
class PonyPeerKeyProvider(
    private val myScalar: ByteArray,
    private val myHandle: String,
) : PonyDirectKeyProvider {
    override fun pairKey(peerID: String): ByteArray? =
        runCatching { PeerKey.deriveFromHandles(myScalar, myHandle, peerID) }.getOrNull()
}
