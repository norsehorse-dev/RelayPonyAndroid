package com.relaypony.transport

/**
 * Cipher-agnostic transfer engine boundary (architecture spec sections 2 and 6).
 *
 * The transport moves opaque, scheme-tagged frames and never inspects payloads, so the same
 * engine serves age (RelayPony) or OpenPGP (PGPony) without change. Real implementation arrives
 * in later phases: the framed wire protocol in Phase 2, shared-network transport in Phase 3,
 * and Wi-Fi Direct in Phase 7.
 */
interface Transport {
    // Phase 2+: open a session and exchange length-prefixed, scheme-tagged frames
    // (HELLO / MANIFEST / per-file chunk stream / ACK / DONE).
}
