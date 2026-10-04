# RelayPony

Encrypted device-to-device file transfer for Android. Pair two devices once, then send photos, videos, documents, folders and text between them. Nearby devices connect directly over Wi-Fi; devices on different networks connect over the internet, directly when they can and through a relay that only sees ciphertext when they can't. No account, no cloud storage, and everything is end-to-end encrypted with [age](https://age-encryption.org).

It is free and open source, with no ads and no tracking. RelayPony 4 for iPhone speaks the same protocol.

Website: https://relaypony.app

## How it works

1. **Pair once.** One device shows a QR code and the other scans it, or one shows a short word code (like `47-lantern-orbit-maple`) and the other types it in. Both devices confirm, and each pins the other's key. Pairing works both ways at once.
2. **Send.** Pick Files, Photos, Folder or Text on Home, then tap a paired device. RelayPony picks the route: nearby and direct on the same Wi-Fi, otherwise over the internet.
3. **Receive.** Receiving is automatic while the app is open. Only paired devices can send to you; anyone else is turned away and listed under Received, so you can pair with them if you want to. Advanced settings can allow unpaired nearby devices.

## Features

- **End-to-end encryption** with age (X25519). The relay never sees file contents or names.
- **QR code and word-code pairing.** Word codes use a PAKE (SPAKE2, in the shared Rust core [RelayPonyPake](https://github.com/norsehorse-dev/RelayPonyPake)) so a short code is safe to read aloud.
- **Paired-only receive.** Every send starts with a HELLO tagged with a key only the two paired devices can derive, so another device can't send while claiming to be one you paired with.
- **Over the internet** through [PonyDirect](https://github.com/norsehorse-dev/PonyDirect-Kotlin): a direct connection when the networks allow it, a relay fallback when they don't. The relay is [self-hostable](https://github.com/norsehorse-dev/RelayPony-Relay).
- **Folders** travel as a zip and are extracted by a strict extractor that refuses unsafe paths, links and oversized archives (tested against a shared corpus of malicious archives).
- **Text and links** arrive as small text files with Copy, plus Open for a single link.
- **Group send** to several paired devices at once, with per-device status.
- **Unpair** from Settings; the other device is told and forgets this one too.
- **Wi-Fi Direct** and **send by address** in Advanced, for networks where devices can't find each other.
- **Identity backup** and address book export, protected by a passphrase.
- **Google TV / Android TV** support.
- **Seven languages**: English, हिन्दी, Español, Deutsch, Français, 日本語, Português (BR), switchable in the app.

The wire protocol is specified in [`docs/PROTOCOL_v3.md`](docs/PROTOCOL_v3.md), with cross-platform test vectors in `docs/vectors/`.

## Project layout

- **`app`**: the Jetpack Compose UI and Android integration (storage, sharing, permissions, QR, discovery).
- **`session`**: sessions and transfers, pairing, the receive gate, signed HELLO, safe zip extraction and folder zipping.
- **`transport`**: discovery and the length-framed wire protocol.
- **`crypto`**: a thin provider over the age implementation.
- **`pake`**: Kotlin bindings for RelayPonyPake's word-code pairing, built from Rust.

Submodules: [AgePonyAndroid](https://github.com/norsehorse-dev/AgePonyAndroid) (age), [PonyDirect-Kotlin](https://github.com/norsehorse-dev/PonyDirect-Kotlin) (internet transport) and [RelayPonyPake](https://github.com/norsehorse-dev/RelayPonyPake) (PAKE core).

## Building

Requirements: JDK 17, the Android SDK (compileSdk 36) and the NDK named by `ndkVersion` in `gradle.properties`, plus rustup and `cargo-ndk` (`cargo install cargo-ndk@4.1.2 --locked`) for the native PAKE library. The Rust toolchain is pinned by RelayPonyPake's `rust-toolchain.toml`.

Clone with submodules:

```
git clone --recursive https://github.com/norsehorse-dev/RelayPonyAndroid.git
```

Then build:

```
./gradlew :app:assembleDebug
```

The `:pake` module runs `scripts/build-pake.sh` during the build to cross-compile the Rust library and generate its bindings. The build always uses the pinned submodules. To develop against sibling checkouts instead, pass `-Prelaypony.useSiblings=true`; a warning says so, and the pins must be bumped before tagging (`tools/check_submodules.sh` checks this).

Release APKs are built on Linux in the container described in [`docker/README.md`](docker/README.md), so they can match F-Droid's build byte for byte. `tools/verify_repro.sh` compares two builds.

## Privacy

No accounts, no analytics, no telemetry. File contents are encrypted end to end. Over the internet, the relay passes encrypted data and pairing messages along and holds them only until they are delivered. Permissions (camera, nearby devices, storage) are requested only when the feature that needs them is used.

## Localization

Each language is a single `app/src/main/res/values-<locale>/strings.xml` file. The translations have not all been reviewed by native speakers, so corrections are very welcome.

## Contributing

Issues and pull requests are welcome. See `CONTRIBUTING.md`.

## License

Apache License 2.0. See `LICENSE`. Copyright 2026 NorseHorse.
