# RelayPony

Encrypted device-to-device file transfer for Android. Send files straight from one phone to another over your local Wi-Fi or a direct Wi-Fi Direct link. No servers, no account, no cloud — the bytes never leave the link between the two devices, and they are end-to-end encrypted the whole way.

RelayPony is built for places and moments where cloud file sharing is the wrong tool: no signal, expensive data, privacy-sensitive files, or simply two phones in the same room. It is free and open source, with no ads and no tracking.

Website: https://relaypony.app

## How it works

1. The receiver opens the **Receive** tab and shows a pairing QR code.
2. The sender scans it once. The recipient's identity is pinned locally, so future transfers to that device need no re-pairing.
3. Files travel directly between the two phones — over the shared Wi-Fi network (discovered via mDNS) or over a direct Wi-Fi Direct link when there is no shared network — encrypted end to end with the [age](https://age-encryption.org) protocol.

## Features

- **Direct, serverless transfer** over local Wi-Fi (mDNS/NSD discovery) or Wi-Fi Direct (no shared network needed).
- **End-to-end encryption** with the age protocol (X25519). The transport is cipher-agnostic by construction.
- **QR-code pairing** with trust pinned to the recipient's handle.
- **Group send** — encrypt and push to several paired devices in parallel, with per-device status.
- **Resilient by design** — the receiver survives a dropped or reset connection and keeps listening; the sender automatically retries transient failures with backoff.
- **Real progress** — determinate progress on both sender and receiver, plus a graceful stop-receiving control.
- **Inbox** — received files are listed with type, size, and time; open them, share them, or save them to Downloads (optionally automatically).
- **Seven languages** — English, हिन्दी, Español, Deutsch, Français, 日本語, Português (BR), switchable in-app without restarting.
- **Light / dark theme**, adaptive launcher icon, and a privacy-first posture throughout.

## Project layout

RelayPony is a small multi-module Gradle build:

- **`app`** — the Jetpack Compose UI (Material 3), `TransferController`, and Android integration (storage, sharing, permissions, QR).
- **`session`** — session orchestration: `Session` (HELLO → MANIFEST → FILE_* → DONE), `SocketTransfer`, parallel fan-out for group send, and the Wi-Fi Direct identity handshake.
- **`transport`** — `NsdDiscovery` (mDNS advertise/discover), `WifiDirectManager` (Wi-Fi P2P), and the length-framed wire protocol.
- **`crypto`** — a thin `CryptoProvider` abstraction over the age implementation, so the session layer never names a specific cipher.

The age implementation itself comes from [AgePony](https://github.com/norsehorse-dev/AgePonyAndroid), included as a git submodule and consumed as a Gradle composite build (see **Building**).

The on-wire framing is versioned and frozen; `WIRE_VERSION` is bumped before any format change so old and new builds can detect a mismatch.

## Building

Requirements: JDK 17 and the Android SDK (compileSdk 36). minSdk is 23, targetSdk 36.

RelayPony's `crypto` module depends on the age core from AgePony, which is included as a git **submodule**, so clone recursively:

```
git clone --recursive https://github.com/norsehorse-dev/RelayPonyAndroid.git
```

If you already cloned without `--recursive`, pull the submodule in:

```
git submodule update --init --recursive
```

Then build the debug APK:

```
./gradlew :app:assembleDebug
```

`settings.gradle.kts` resolves AgePony in two ways. If a sibling checkout exists at `../AgePonyAndroid` (the local development layout) it is used, so edits to AgePony are picked up immediately. Otherwise the bundled `AgePonyAndroid` submodule is used, which is what lets a fresh clone — and a reproducible F-Droid build — work on its own. Either way, the substitution that maps `com.agepony:agepony-core` to the local `:agepony-core` project lives in the same file.

## Privacy

RelayPony has no backend. There are no accounts, no analytics, and no telemetry. File contents are encrypted end to end and are only ever transmitted directly between the two paired devices on the local link. Permissions (camera, nearby devices, storage) are requested only when the feature that needs them is used.

## Localization

The app ships in seven languages. The non-English translations are a solid starting point but have not yet been reviewed by native speakers — corrections and review are very welcome (each language is a single `res/values-<locale>/strings.xml` file).

## Contributing

Issues and pull requests are welcome. See `CONTRIBUTING.md` for the basics. Translation review, bug reports from real-world transfers, and reproducibility work toward an F-Droid release are all especially appreciated.

## License

Apache License 2.0 — see `LICENSE`. Copyright 2026 NorseHorse.
