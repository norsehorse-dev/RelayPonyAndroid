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
  | `relaypony/hello/v1` | HELLO sender tag (section 9) | 4.0 |

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

## 9. Authenticated HELLO and paired-only receive

The LAN listener accepts connections from anyone on the network, and the HELLO's name and handle
are whatever the sender typed. 4.0 binds the handle to its owner and receives from paired devices
only by default (plan section 9.2, audit 2.4).

### 9.1 Sender tag

A 4.0 sender appends a tail to its HELLO payload, after the handle (v1) or the caps tail (v2):

```
tail = "RPH1" || u64be(timeMs) || tag                    44 bytes
tag  = HMAC(K("relaypony/hello/v1"), "RPH1" || u64be(timeMs) || body)
```

`body` is the HELLO payload before the tail and `timeMs` the sender's clock. Readers before 4.0
ignore trailing HELLO bytes (pinned by tests since 2.0), so the tail is invisible to them. The
WAN path doesn't need it: its streams are already keyed by the pair key.

### 9.2 Receiver decision

The receiver reads the HELLO and, before anything else:

1. **Verified**: `from` (the HELLO handle) is pinned, the tag verifies under the pair key with
   `from`, `timeMs` is within 10 minutes of the receiver's clock, and the tag hasn't been seen in
   the last 20 minutes. The device is remembered as one that tags its HELLO.
2. **Paired, untagged**: `from` is pinned, there is no tail, and `from` has never sent a tagged
   HELLO. A 3.x device: accepted, as in 3.x.
3. **Unpaired allowed**: Advanced, "Accept from unpaired nearby devices" is on. Accepted, and
   labelled as not paired.
4. Otherwise the transfer is **refused**. That includes a pinned handle with a bad or stale tag,
   and a pinned handle that has tagged before but now sends no tail (a downgrade).

Received files are labelled with the pinned name, never the HELLO name, unless case 3 applies.

### 9.3 Refusal

A refused v2 sender gets, instead of the receiver's HELLO, one frame:

```
0x09 REFUSE   payload "RPR1|" reason        reason: "unpaired"
```

and the connection closes. A 4.0 sender reads its first answer frame and reports "<name> doesn't
have this device paired". A v1 sender, or a 3.x v2 sender (which only expects HELLO), sees the
connection fail, as an older receiver would show it. The receiver keeps a short list of refused
senders (the Requests card in Received) so its user can pair back.

### 9.4 Limits

The tag binds the HELLO, not the frames after it. Someone on the LAN who captures a tagged HELLO
and blocks the original could send their own files under that device's name within the freshness
window; the replay cache stops a second use. Capability negotiation stays inert (`LOCAL_CAPS` is
0). Before a security-relevant capability ships, the receiver's answering HELLO must carry its own
tag over both bodies and the sender must verify it.

## 10. Compatibility

| Pairing | Result |
|---|---|
| 4.0 shows QR, 3.x scans | Rejected by 3.x. Use "Show legacy QR". |
| 3.x shows QR, 4.0 scans | One-way pin, as in 3.x, with a note. |
| Word code with a 3.x peer | Not possible: "The other device needs RelayPony 4". |
| WAN with a 3.x peer | Works through 4.x: handle addressing plus the plaintext signaling copy. |
| 4.0 on a 1.x self-hosted relay | QR pairing and WAN work with handle addressing; no word codes, no inboxIds. |
| 3.x sends to 4.0 over the LAN | Accepted if the 3.x device is paired here (untagged HELLO); otherwise refused and listed under Requests. |

## 11. Test vectors

- `vectors/gen_p1_vectors.py` is an independent Python implementation of sections 3 to 5 and 7.2.
  It writes `vectors/p1_vectors.json`, and the Kotlin (`P1VectorsTests`) and Swift
  (`P1VectorsTests`) suites assert the same values. The fixed inputs reuse the PeerKey vector:
  scalarA = 0x01..0x20, scalarB = 0x20..0x01.
- The PAKE vectors live in RelayPonyPake (`vectors/pake_vectors.json`, seeded RNG, checked by
  `cargo test`). Interop with python-spake2, the magic-wormhole implementation, was confirmed by
  opening a Rust-sealed data message from a Python peer.
- `vectors/gen_zip_corpus.py` builds hostile and edge-case archives byte by byte for the folder
  extractor (section 12) and writes `vectors/zip_corpus.json`, each case with its expected
  result: the root, paths, contents, directories and skip count, or the refusal reason and
  whether it is found while planning or while writing. Kotlin and Swift `SafeZipTests` run it.

## 12. Folders

A folder travels as one ordinary file: a zip archive named `<folder>.zip` with mime
`application/zip`. Nothing on the wire changes, so a 3.x receiver simply gets a zip. A 4.0
receiver offers Extract on any received `.zip` and treats every archive as hostile, whoever sent
it.

### 12.1 Sending

- The archive's single top-level directory is the folder itself: `Photos/`, `Photos/a.jpg`,
  `Photos/trip/b.jpg`. Empty directories get their own `dir/` entries.
- Methods are stored (0) or deflate (8), with zip64 records when counts, sizes or offsets need
  them. Android writes deflate at level 0, since photos and video do not shrink. iOS uses the
  system zipper (`NSFileCoordinator` with `.forUploading`).
- The zip is built in the app's cache before the send and deleted when the transfer screen
  closes. The free space check covers the folder's size plus the usual margin.

### 12.2 Reading the archive

Only the central directory is trusted for names, flags and sizes. Checks run in this order and
the first failure refuses the whole archive (section 12.5 lists the reasons):

1. The end-of-central-directory record must be the last 22 bytes plus its comment, found by
   scanning back at most 65,557 bytes. None found, or a file under 22 bytes: `NOT_A_ZIP`.
   Trailing bytes after the comment also give `NOT_A_ZIP`.
2. Disk numbers must be 0 and the entry count on this disk must equal the total, else
   `MULTI_DISK`.
3. If the entry count, directory size or directory offset is saturated (0xFFFF or 0xFFFFFFFF),
   the zip64 locator must sit 20 bytes before that record and point at a zip64 record placed
   before it; otherwise `CORRUPT`. The zip64 record's values replace the saturated ones and its
   disk fields are checked as in step 2.
4. More entries than `maxEntries` (10,000 by default): `TOO_MANY_ENTRIES`.
5. The central directory must end at or before the (zip64) end record and be at most 64 MiB,
   else `CORRUPT`.
6. Each entry in directory order:
   1. Header signature, lengths and zip64 extra field (id 0x0001: uncompressed size, compressed
      size, local offset, disk, each present only when its field is saturated) must parse, else
      `CORRUPT`. A disk number other than 0 is `MULTI_DISK`.
   2. General-purpose flag bit 0 (encrypted): `ENCRYPTED`. A method other than 0 or 8:
      `UNSUPPORTED_METHOD`.
   3. The path rules of section 12.3.
   4. A file (not a directory) larger than `maxFileBytes`: `TOO_LARGE`.
   5. The local header at the entry's offset must carry its signature; its name and extra
      lengths give where the data starts, and the data must end at or before the central
      directory. A stored entry's two sizes must match. Any failure is `CORRUPT`.
   6. Skipped, never written: Unix symlinks (made-by host 3 and mode `0xA000` in the high 16 bits
      of the external attributes), anything under a `__MACOSX` component, `.DS_Store` files, and
      entries with no path left. Only skipped files are counted, not skipped directories.
   7. The running total of kept files larger than `maxTransferBytes`: `TOO_LARGE`.
7. Sorted by local header offset, no entry's span (local header through the end of its data)
   may overlap the next one: `OVERLAP`. This stops archives that reuse one body under many names.

### 12.3 Paths

1. Names decode as UTF-8 when the bytes are valid UTF-8, otherwise as ISO-8859-1. Then `\`
   becomes `/`, and a trailing `/` marks a directory.
2. A character below U+0020 or equal to U+007F: `UNSAFE_PATH`. A leading `/`, or a name that
   starts with a drive letter and colon (`C:`): `UNSAFE_PATH`.
3. The name splits on `/`. Empty and `.` components are dropped. A component whose NFKC form is
   `..`, or contains `/` or `\`, is `UNSAFE_PATH` (this catches fullwidth dots and slashes). One
   whose NFKC form is `.` is dropped.
4. More than 32 components: `TOO_DEEP`.
5. Each kept component is cleaned: NFC; each of `" * : < > ? |` becomes `_`; trailing spaces
   and dots and leading spaces are removed; an empty result becomes `_`; then it is capped at
   200 UTF-8 bytes, keeping an extension of up to 16 characters.
6. Root: when there is at least one file, every kept entry has the same first component, and
   every file has at least two components, that component is the root folder and is removed
   from every path. Otherwise the root is the archive name without `.zip` (any case), cleaned as
   a component, or `Folder` when nothing is left. The receiver creates the root under its own
   destination and picks a free name if it is taken.
7. Collisions are checked case-insensitively (Unicode lower case), in directory order. Each
   directory prefix of an entry, and each directory entry, claims its path as a folder; a path
   already claimed as a file is `CONFLICT`. A file whose path is already a folder is `CONFLICT`.
   A file whose path is already a file is renamed `name (2).ext`, then `(3)` and so on, until
   it is free. Directory entries are reported once each, in order, for receivers that create
   empty folders; Android does not.

### 12.4 Writing

- Before anything is written, the declared total of kept files plus the free space margin must
  fit in the destination's free space, else `NO_SPACE`.
- Each file is written through the receiver's own sink at the planned path. Inflate output is
  stopped as soon as it would pass the declared size. The file must end at exactly the declared
  size, with a matching CRC-32, and a deflate stream must finish inside its compressed span (no
  padding byte is added). Anything else is `CORRUPT`.
- On any failure the receiver removes everything it wrote for this archive. Links, devices and
  permissions in the archive are never applied: every output is a plain new file.

### 12.5 Refusal reasons

`NOT_A_ZIP`, `MULTI_DISK`, `CORRUPT`, `ENCRYPTED`, `UNSUPPORTED_METHOD`, `UNSAFE_PATH`,
`TOO_DEEP`, `TOO_MANY_ENTRIES`, `TOO_LARGE`, `NO_SPACE`, `CONFLICT`, `OVERLAP`. The apps show one
plain sentence per reason; the names are for tests and logs.
