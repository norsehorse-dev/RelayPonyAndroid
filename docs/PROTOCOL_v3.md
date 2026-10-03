# RelayPony protocol v3 (RelayPony 4.0)

Status: draft, Rev 1, October 2026. Normative for RelayPony 4.0 on Android and iOS and for
RelayPony-Relay 2.0. Where this document and code disagree, the shared test vectors win, then
this document.

Everything in protocol v2 (the LAN wire, HELLO v1/v2, sessions, WAN transfer, PonyDirect) stays
as it is unless a section below changes it. Section 10 lists what a 3.x peer can and can't do with
a 4.0 peer.

---

## 1. Notation and building blocks

- `||` is byte concatenation. Strings are UTF-8 with no terminator.
- `b64u(x)` is base64url without padding (RFC 4648 section 5). `hex(x)` is lowercase hex.
- `form(s)` is `application/x-www-form-urlencoded` as Java's `URLEncoder` produces it (space
  becomes `+`). Both platforms already implement this for QR v1.
- `HKDF(ikm, salt, info)` is HKDF-SHA256 with a 32-byte output.
- `HMAC(k, m)` is HMAC-SHA256.
- `age_seal(to, m)` is an age v1 file (X25519 recipient `to`) with plaintext `m`. `age_open` is the
  inverse. age gives confidentiality only; it is anonymous.
- **Handle**: a device's age X25519 recipient, `age1...`. Unchanged from 3.x.
- **Pair key family**: for devices A and B, with `lo`/`hi` the two handles sorted as strings,

  ```
  K(info) = HKDF(X25519(mySecret, peerPublic), lo || 0x00 || hi, info)
  ```

  Both sides compute the same value. The `info` strings in use:

  | info | Use | Since |
  |---|---|---|
  | `relaypony/ponydirect/peerkey/v1` | PonyDirect handshake and stream MACs | 3.0 |
  | `relaypony/signal/mac/v1` | Sealed signaling tag (section 6) | 4.0 |
  | `relaypony/pair/ack/v1` | PAIR_ACK tag (section 4.4) | 4.0 |
  | `relaypony/inbox/v1` | Inbox announcement tag (section 5.3) | 4.0 |
  | `relaypony/unpair/v1` | Unpair notice tag (section 4.7) | 4.0 |
  | `relaypony/hello/v1` | Reserved for authenticated HELLO (section 9) | reserved |

  A key from one `info` is never used for another purpose.

- **Relay base URL**: the scheme, host and optional port of a relay, for example
  `https://relaypony.app`. Normalized form: scheme and host lowercased, default port dropped, no
  path, no trailing slash. The empty string means the default relay, `https://relaypony.app`.

## 2. Identifiers

| Name | Form | Public? | Notes |
|---|---|---|---|
| handle | `age1...` | Yes | Shown in QR, beacon, mDNS. |
| inboxId | `b64u(32 random bytes)`, 43 chars | No | This device's private relay mailbox. Shared only with paired devices. Rotatable (Advanced, Identity). |
| pairNonce | 16 random bytes | Short-lived | Fresh every time the pairing QR is shown. Section 3. |
| nameplate | Decimal integer 1 to 9999 | Short-lived | Handed out by the relay for word-code pairing. Section 7. |

A paired-device record gains two optional fields: `inboxId` and `relay` (normalized base URL, empty
for the default). Records written by 3.x have neither; section 5.3 fills them in.

## 3. Pairing QR v2

```
RP2|<scheme 2-hex>|<handle>|<form(deviceName)>|<b64u(pairNonce)>|<inboxId>|<form(relay)>
```

- Seven `|`-separated fields. `scheme` is `01` (age). `relay` is empty for the default relay.
- `pairNonce` is generated each time the QR is displayed. It is valid for **5 minutes** and **one
  use**. Only the most recently displayed nonce is valid. Showing a new QR retires the old one.
- A 3.x scanner rejects `RP2` (it expects 4 fields), which is intended. The pair sheet offers
  "Show legacy QR" (an `RP1` code) for 30 days after release.
- A 4.0 scanner still accepts `RP1`. That pins one way, as in 3.x, and the UI says the other
  device needs RelayPony 4 for two-way pairing.

Decoding rules: exactly 7 fields; `RP2` literal; `pairNonce` decodes to 16 bytes; `inboxId`
decodes to 32 bytes; the handle starts with `age1`. Anything else is rejected.

## 4. Mutual pairing (QR path)

A shows the QR. B scans it. B trusts A because the camera read A's handle. A trusts B because the
users compare a code bound to a nonce only B knows.

### 4.1 PAIR_REQ (B to A)

B picks `nonceB`, 16 random bytes, and sends:

```
plain = "RPQ1|" handleB "|" form(nameB) "|" inboxB "|" form(relayB) "|" b64u(nonceA) "|" b64u(nonceB)
msg   = age_seal(handleA, plain)
```

No MAC: A has no key relationship with B yet. The code comparison authenticates B.

### 4.2 Checks on A

A rejects the request unless all hold:

1. `nonceA` equals A's current pairNonce, it is under 5 minutes old, and it has not been used.
   A valid request marks it used. A second request carrying the same nonce is ignored.
2. `handleB` is a valid age1 handle and is not A's own handle.
3. The fields parse as in 4.1.

### 4.3 SAS v2

```
digest = SHA-256("relaypony-sas-v2" || 0x00 || lo || 0x00 || hi || 0x00 || nonceA || nonceB)
code   = (big-endian u64 of digest[0..8]) mod 10^8, zero-padded to 8 digits
```

`lo`/`hi` are the two handles sorted as strings. `nonceA` is always the QR shower's nonce and
`nonceB` the scanner's, regardless of sort order. Shown as `dddd dddd`.

A shows "Pair with <nameB>? 4821 0937" with Confirm and Cancel. B shows "Waiting for <nameA> to
confirm 4821 0937" with Cancel.

Why it holds: an attacker who saw the QR can send its own PAIR_REQ, but B's nonce travels
encrypted to A, so the attacker can't steer its code to match the one on B's screen. It gets one
blind 1-in-10^8 attempt, and the nonce is then spent.

### 4.4 PAIR_ACK (A to B)

When the user confirms on A, A pins B (handle, name, inboxB, relayB) and sends:

```
body = "RPA1|" handleA "|" handleB "|" b64u(nonceB) "|" result          result: "ok" or "no"
tag  = HMAC(K("relaypony/pair/ack/v1"), body)
msg  = age_seal(handleB, body "|" hex(tag))
```

On Cancel, A sends `result = no` (best effort) and pins nothing.

B accepts only if it has a pending request with that `nonceB` to that `handleA` (the one it
scanned), the tag verifies, and the request is under 5 minutes old. On `ok` it pins A with the
name, inboxId and relay from the QR. The tag proves the ACK came from the holder of A's secret.
`no` (or a timeout) ends B's wait with "Pairing was cancelled on <nameA>."

### 4.5 Delivery

PAIR_REQ and PAIR_ACK are delivered by both of these when available, and the receiver de-dupes on
`nonceB`:

- **Relay**: `mbox.send` to the peer's inboxId on the peer's relay (section 8). PAIR_REQ uses
  inboxA and relay from the QR; PAIR_ACK uses inboxB and relayB from the request.
- **LAN**: if the peer is currently discovered and advertises `pr=1` (TXT key and beacon flag), open
  a TCP connection to its transfer port and send one frame of type `0x08 PAIR` whose payload is
  `msg`, then close. A 4.0 listener reads the first frame and branches on `PAIR` versus `HELLO`. A
  3.x listener rejects the connection, which is harmless because it never advertises `pr=1`.

Both PAIR messages are age files under 16 KiB. The LAN path is what lets two phones pair with no
internet at all.

LAN details, as implemented:

- **Advertising.** mDNS TXT `pr=1`. The Android beacon ANNOUNCE gains a trailing flags byte after
  the handle, `[flags u8]`, bit 0 = accepts PAIR, written only when a bit is set. 3.x decoders stop
  after the handle, so they see the same frame they always did. A build that doesn't route its
  listener through the PAIR branch must not advertise it.
- **Frame.** `[0x08][u32 length][msg]`, length at most 16384. A larger declared length, a truncated
  frame or a connection that ends early is dropped silently; the relay copy is the fallback.
- **Listener.** The first frame must arrive within 30 seconds of the connection being accepted,
  after which a session runs with no read timeout, as in 3.x. On the PAIR path only `RPQ1`, `RPA1`
  and `RPU1` (section 4.7) are honoured. Anything else that opens (an `RPI1` inbox announcement,
  signaling) is dropped, because the listener accepts connections from anyone on the network.
  `RPU1` is allowed because it is tagged under a pair key, so only a paired device can produce one.
- **Lifetime.** While the pair sheet is open, the device runs its listener and discovery even if
  the user paused receiving, and restores both when the sheet closes. A sender that doesn't see the
  peer yet probes (Android) or re-checks discovery (iOS) for a few seconds before giving up on the
  LAN copy.

### 4.6 Pair-and-send

If B had content staged, the transfer starts as soon as B pins A. A can't send to B until A's user
confirms, and that confirmation is the same tap.

### 4.7 Unpairing

Either device can unpair the other. It removes the peer's pin and route on its own side, and tells
the peer, best effort, so it does the same:

```
body = "RPU1|" from "|" to "|" atMs          atMs: the sender's clock, decimal ms since the epoch
tag  = HMAC(K("relaypony/unpair/v1"), body)
msg  = age_seal(to, body "|" hex(tag))
```

It goes to the peer's inbox on the peer's relay when the route is known, to the peer's handle on the
sender's relay as well (so a peer that never announced an inbox still gets it), and as a LAN `PAIR`
frame when the peer is discovered with `pr=1`.

The receiver drops it unless `to` is its own handle, `from` is pinned, the tag verifies, and `atMs`
is later than the time it pinned `from`. The last check stops a captured notice from undoing a later
re-pairing. If the sender's clock runs behind the receiver's by more than the time the two were
paired, a genuine notice is dropped too, which leaves the receiver as it was. On success the receiver
removes the pin, the route and any record that `from` uses its inbox, and tells the user
"<name> unpaired from this device." It sends nothing back.

Unpairing doesn't rotate the inbox. The former peer still knows it, but anything it posts there is
sealed signaling from an unpinned handle and is dropped.

## 5. Private inboxes

### 5.1 Addressing

Every 4.0 device polls two relay addresses while it is ready to receive:

- its **inboxId**, with `mbox.poll`, on its own relay, and
- its **handle**, with the legacy `poll`, on its own relay, for 3.x peers. This stays through all of
  4.x and is dropped in 5.0.

A sender uses the peer's inboxId and relay from the paired-device record when it has them, and
otherwise the peer's handle on the sender's own relay (the 3.x behaviour).

### 5.2 What the relay sees

With inboxIds, someone who knows only a handle can no longer reach the device's queue. Blobs are
sealed (section 6), so the relay operator sees mailbox ids, blob sizes and timing, never content,
candidates or handles inside.

### 5.3 Inbox announcement

A device sends its inboxId and relay to a paired peer that doesn't have them yet (all 3.x-era
pairs, and every pair after a rotation):

```
body = "RPI1|" from "|" to "|" inboxId "|" form(relay)
tag  = HMAC(K("relaypony/inbox/v1"), body)
msg  = age_seal(to, body "|" hex(tag))
```

It goes out alongside the first sealed signaling blob of any WAN transfer to that peer, until the
peer is seen polling the new inbox (its next transfer arrives through it). The receiver verifies
the tag, then stores `inboxId` and `relay` on the pinned record for `from`. An announcement from an
unpinned handle is dropped.

## 6. Sealed signaling (shipped in P0)

As implemented in 4.0 P0, restated here for completeness:

```
inner  = "RPS2|" kind "|" from "|" to "|" hex(sessionNonce) "|" candidates
tag    = HMAC(K("relaypony/signal/mac/v1"), inner)
msg    = age_seal(to, inner "|" hex(tag))
```

A sender sends a plaintext `RPS1` copy as well until the peer has been seen sending sealed blobs.
After that it sends sealed only, and a plaintext blob claiming to come from that peer is dropped.

### 6.1 Dispatch

Every relay payload is standard base64 of the message bytes. A payload whose bytes start with
`age-encryption.org/v1` is sealed: after `age_open`, the plaintext prefix decides the type, `RPS2|`
signaling, `RPQ1|` PAIR_REQ, `RPA1|` PAIR_ACK, `RPI1|` inbox announcement or `RPU1|` unpair notice. A payload whose bytes
start with `RPK1|` is a PAKE message (section 7, not sealed, see 7.3), and one starting with `RPS1|`
is legacy plaintext signaling. Anything else is dropped.

## 7. Word-code pairing

### 7.1 Code

```
<nameplate>-<w1>-<w2>-<w3>        e.g. 47-lantern-orbit-maple
```

- `nameplate` comes from the relay (`nameplate.claim`).
- Words come uniformly from the EFF short wordlist 1 (CC BY 3.0 US) minus its one hyphenated
  entry, `yo-yo`, which would collide with the separator and duplicates `yoyo` anyway. That leaves
  1,295 words; three give about 31 bits.
- Normalization before use: trim, lowercase, collapse runs of whitespace, `-`, `_` or `.` into a
  single `-`, and drop leading or trailing separators. The PAKE password is the UTF-8 of the whole
  normalized code, nameplate included.
- Custom codes (Advanced, Pairing, "Use my own code"): `<nameplate>-<passphrase>`, where the
  passphrase has at least 3 words or 20 characters. Same normalization.
- A code is valid for 10 minutes and one pairing.

### 7.2 Mailboxes

Each side has a mailbox on the claimer's relay. Side `A` is the claimer, side `B` the one who
types the code.

```
mbox(side) = b64u(SHA-256("rp-pake-v1" || 0x00 || ascii(nameplate) || 0x00 || relay || 0x00 || side))
```

`relay` is the normalized relay base URL as in section 1 (empty for the default) and `side` is the
single byte `A` or `B`. Each side polls its own mailbox and posts to the other's. The ids depend
on the nameplate only, never on the words, so the relay learns nothing it could test guesses with.

When B types a code, the default relay is assumed. A device using a custom relay shows the relay
under the code ("on relay.example.com") and the typing side enters it once.

### 7.3 Flow

All PAKE crypto is in the shared Rust core, RelayPonyPake (section 7.4). Messages are text lines
posted with `mbox.send`. They are not age-sealed: neither side has the other's handle yet, and the
PAKE does the protecting.

1. Each side starts SPAKE2 (symmetric, Ed25519 group, identity `relaypony/pake/v1`) with the
   password and posts `RPK1|pake|<b64u(spake2 message)>`.
2. On receiving the other side's message, each side finishes and gets `Kp` (32 bytes).
3. Each side posts `RPK1|data|<b64u(sealed)>`, where `sealed` is ChaCha20-Poly1305 under
   `HKDF(Kp, "", "relaypony/pake/data/" side)` with a 12-byte zero nonce (one message per key) and
   plaintext `RPX1|handle|form(name)|inboxId|form(relay)`.
4. A side that fails to open the peer's `data` message reports "That code didn't match. Check it
   and try again." on its screen and posts nothing more. The nameplate is released and the code is
   spent. This is the one online guess an attacker gets.
5. When both `data` messages open, both sides pin each other. No code comparison is needed.
6. A releases the nameplate. If either side had content staged, the transfer starts.

Timeouts: 2 minutes from the first post without a peer message ends the attempt with "Code not
found or expired".

### 7.4 RelayPonyPake API

A Rust crate wrapping RustCrypto `spake2` (the crate magic-wormhole.rs uses), exported with UniFFI
to Kotlin and Swift:

- `normalize_code(code) -> String`, `generate_code(nameplate) -> String`, `validate_custom(code) -> bool`
- `PakeSession::new(code, side) -> PakeSession`, `.outbound() -> bytes`, `.finish(peer) -> ()`
- `.seal_data(plain) -> bytes`, `.open_data(sealed) -> bytes` (throws on mismatch)
- `mailbox_id(nameplate, relay, side) -> String`

Deterministic test vectors (seeded RNG) are generated by the crate and checked by the Kotlin and
Swift tests.

## 8. Relay 2.0

All ops are `POST /api/signal` with a JSON body, as in 1.x. New in 2.0:

| op | Request | Response |
|---|---|---|
| `info` | `{}` | `{ok, version: "2.0", features: ["mbox","nameplate","sealed","ratelimit"]}` |
| `mbox.send` | `{to: <inboxId or pake mbox>, payload: <base64, ≤16 KB>}` | `{ok}` |
| `mbox.poll` | `{for: <inboxId or pake mbox>}` | `{ok, blobs: [...]}`, delete-on-fetch |
| `nameplate.claim` | `{}` | `{ok, nameplate, token, ttl: 600}` |
| `nameplate.release` | `{nameplate, token}` | `{ok}` |

- Mailbox ids are exactly 43 base64url characters. `send`/`poll` keep accepting `age1` handles for
  3.x clients through all of 4.x.
- Nameplates are random integers from 1 to 999 while fewer than half are in use, then 1 to 9999.
  They expire after 10 minutes. Only the holder of `token` can release one early.
- Per-IP rate limits (per 60 s on a salted hash of the IP): `poll`, `send`, `mbox.poll`,
  `mbox.send` 300 each; `nameplate.claim` 20.
- Clients detect an older relay by `info` failing or lacking `mbox`. Then: inboxIds are not used
  (handle addressing only), and word codes show "Your relay server needs updating for word codes.
  QR pairing still works."

## 9. Capability binding (reserved)

`LOCAL_CAPS` stays 0 in 4.0, so HELLO capability negotiation remains inert. Before any capability
bit that affects security ships, HELLO must be authenticated:

```
HELLO v3 = HELLO v2 body || tag,  tag = HMAC(K("relaypony/hello/v1"), "RPH1" || myBody || peerBody)
```

The sender's tag covers its own body only (it hasn't seen the answer). The receiver's answering
HELLO tag covers both bodies, and the sender verifies it before trusting the negotiated caps. A
mismatch aborts the session. Not built in 4.0; recorded here so it isn't forgotten.

## 10. Compatibility

| Pairing | Result |
|---|---|
| 4.0 shows QR, 3.x scans | Rejected by 3.x. Use "Show legacy QR". |
| 3.x shows QR, 4.0 scans | One-way pin, as in 3.x, with a note. |
| Word code with a 3.x peer | Not possible: "The other device needs RelayPony 4". |
| WAN with a 3.x peer | Works through 4.x: handle addressing plus the plaintext signaling copy. |
| 4.0 on a 1.x self-hosted relay | QR pairing and WAN work with handle addressing; no word codes, no inboxIds. |

## 11. Test vectors

- `vectors/gen_p1_vectors.py` is an independent Python implementation of sections 3 to 5 and 7.2.
  It writes `vectors/p1_vectors.json`, and the Kotlin (`P1VectorsTests`) and Swift
  (`P1VectorsTests`) suites assert the same values. The fixed inputs reuse the PeerKey vector:
  scalarA = 0x01..0x20, scalarB = 0x20..0x01.
- The PAKE vectors live in RelayPonyPake (`vectors/pake_vectors.json`, seeded RNG, checked by
  `cargo test`). Interop with python-spake2, the magic-wormhole implementation, was confirmed by
  opening a Rust-sealed data message from a Python peer.
