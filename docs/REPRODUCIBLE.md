# Reproducible builds

RelayPony 4.0 is the first release with native code: RelayPonyPake, the word-code pairing core
(Rust, through UniFFI). It follows PassPonyAndroid's setup, which is the reference for the full
reasoning and incident history; this file lists what is pinned here.

| Input | Pinned value | Where |
| --- | --- | --- |
| Android NDK | 27.2.12479018 | `gradle.properties` (`ndkVersion`), read by `:app`, `:pake` and `scripts/build-pake.sh`; `docker/release.Dockerfile` by hand |
| NDK path seen by cargo-ndk | `/tmp/relaypony-ndk` (symlink) | `scripts/build-pake.sh` |
| Rust toolchain | RelayPonyPake's `rust-toolchain.toml` (1.95.0) | RelayPonyPake |
| cargo-ndk | 4.1.2 | Dockerfile, F-Droid recipe |
| RelayPonyPake commit | the `RelayPonyPake` submodule | `.gitmodules` / gitlink |
| Rust source paths | remapped to `/relaypony-pake` and `/home` | `scripts/build-pake.sh` (`RUSTFLAGS`) |
| Git commit and dependency info in the APK | excluded | `app/build.gradle.kts` (`vcsInfo`, `dependenciesInfo`) |

RelayPonyPake is pure Rust with no C build, so PassPony's OpenSSL-specific pins (fixed cargo
target dir, forced `SOURCE_DATE_EPOCH`) don't apply. Releases are still built in the Linux
container (`docker/README.md`) because the NDK's linker differs per host OS.

Checks:

- `tools/verify_repro.sh rebuild <tag> [candidate.apk]` builds the tag twice from clean clones and
  compares the APK contents (never extracting to disk).
- `tools/verify_repro.sh content-hash <apk>` is the hash to publish in release notes.

## Submodule pins

Every build uses the pinned submodules (AgePonyAndroid, PonyDirect-Kotlin, RelayPonyPake) by
default, the same code a fresh clone, the release container and F-Droid build. Building against a
sibling checkout is opt-in: `./gradlew -Prelaypony.useSiblings=true …` (or
`relaypony.useSiblings=true` in `~/.gradle/gradle.properties`, or `RELAYPONY_USE_SIBLINGS=1`), and
the build warns when it does. Before tagging, `bash tools/check_submodules.sh` must pass: each pin
checked out cleanly, pushed to its remote, and matching any sibling checkout (issue #3).
