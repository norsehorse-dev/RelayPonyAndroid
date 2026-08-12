package com.relaypony.transport

/**
 * Wire-version and capability negotiation (B2). Pure functions so the four-way matrix (v1/v2
 * sender x v1/v2 receiver) is exhaustively unit-testable without any socket. Mirrors the iOS
 * Negotiation so the two implementations agree by construction.
 *
 * The rule (PROTOCOL_v2_draft.md §2): a build advertises the highest version it speaks as `mw`,
 * and the sender speaks min(its own max, the peer's advertised max), floored at 1. Effective
 * capabilities are the bitwise AND of the two HELLOs' masks — a feature is on only if both offer it.
 */
object WireNegotiation {

    /** The wire version to speak with a peer, floored at 1 so a garbage advert can't drop below v1. */
    fun version(localMax: Int, peerMax: Int): Int = maxOf(1, minOf(localMax, peerMax))

    /** Effective capabilities: on only where both sides offer the bit. */
    fun effectiveCaps(local: Int, peer: Int): Int = local and peer
}
