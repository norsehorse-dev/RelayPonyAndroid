#!/usr/bin/env bash
# Cross-compile RelayPonyPake's pake-ffi for Android and generate its Kotlin UniFFI bindings.
# Run from the repo root: bash scripts/build-pake.sh
#
# Adapted from PassPonyAndroid's scripts/build-core.sh. RelayPonyPake is pure Rust (no vendored
# OpenSSL or other C build), so the OpenSSL-specific pins PassPony needs don't apply; the path
# remapping and the fixed NDK symlink are kept because they still decide byte-identity.
#
# Prereqs: rustup (the toolchain is pinned by RelayPonyPake's rust-toolchain.toml), cargo-ndk
# (cargo install cargo-ndk@4.1.2 --locked), the NDK named by gradle.properties' ndkVersion,
# python3 (standard library only).

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Where RelayPonyPake lives. The pinned submodule is the default, because that is what a fresh
# clone, the release container and F-Droid build (see settings.gradle.kts and issue #3 for why a
# silent sibling fallback is a trap). A different checkout is used only when asked for:
# RELAYPONY_PAKE=/path, or RELAYPONY_USE_SIBLINGS=1 (also set by ./gradlew -Prelaypony.useSiblings=true)
# for ../RelayPonyPake or ~/Apps/RelayPonyPake.
CORE=""
if [[ -n "${RELAYPONY_PAKE:-}" ]]; then
  CORE="$RELAYPONY_PAKE"
elif [[ -d "$REPO/RelayPonyPake/crates/pake-ffi" ]]; then
  CORE="$REPO/RelayPonyPake"
elif [[ "${RELAYPONY_USE_SIBLINGS:-}" == "1" ]]; then
  for c in "$REPO/../RelayPonyPake" "$HOME/Apps/RelayPonyPake"; do
    [[ -d "$c/crates/pake-ffi" ]] && { CORE="$c"; break; }
  done
fi
[[ -n "$CORE" && -d "$CORE/crates/pake-ffi" ]] || {
  echo "RelayPonyPake not found. Run: git submodule update --init --recursive (or set RELAYPONY_PAKE, or RELAYPONY_USE_SIBLINGS=1 for ../RelayPonyPake or ~/Apps/RelayPonyPake)"; exit 1; }
CORE="$(cd "$CORE" && pwd)"
if [[ "$CORE" != "$REPO/RelayPonyPake" ]]; then
  echo "WARNING: building RelayPonyPake from $CORE, NOT the pinned submodule. Release and F-Droid builds use the submodule; bump its pin before tagging." >&2
fi

command -v cargo >/dev/null || { echo "cargo not found; install rustup first"; exit 1; }
command -v cargo-ndk >/dev/null || { echo "cargo-ndk not found; run: cargo install cargo-ndk@4.1.2 --locked"; exit 1; }
command -v python3 >/dev/null || { echo "python3 not found"; exit 1; }

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  NDK_VERSION="$(sed -n 's/^ndkVersion=//p' "$REPO/gradle.properties")"
  [[ -n "$NDK_VERSION" ]] || { echo "Could not read ndkVersion from $REPO/gradle.properties"; exit 1; }
  ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/$NDK_VERSION"
  [[ -d "$ANDROID_NDK_HOME" ]] || {
    echo "NDK $NDK_VERSION not found at $ANDROID_NDK_HOME; install it with: sdkmanager \"ndk;$NDK_VERSION\" (or set ANDROID_NDK_HOME)"; exit 1; }
  export ANDROID_NDK_HOME
fi

# Present the NDK at one fixed path on every host, so nothing path-dependent can differ between a
# local build, the Docker release container and F-Droid's buildserver (/opt/android-sdk).
NDK_LINK="/tmp/relaypony-ndk"
[[ -L "$NDK_LINK" || ! -e "$NDK_LINK" ]] || { echo "$NDK_LINK exists and is not a symlink; remove it and re-run"; exit 1; }
ln -sfn "$ANDROID_NDK_HOME" "$NDK_LINK"
export ANDROID_NDK_HOME="$NDK_LINK"

pushd "$CORE" >/dev/null

# Inside the core dir, rustup uses the toolchain pinned by its rust-toolchain.toml.
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

# rustc bakes absolute source paths into panic-location strings, which survive strip; normalize
# them so two machines with different checkout paths produce the same bytes.
export RUSTFLAGS="--remap-path-prefix=$CORE=/relaypony-pake --remap-path-prefix=$HOME=/home${RUSTFLAGS:+ $RUSTFLAGS}"

JNILIBS="$REPO/pake/src/main/jniLibs"
echo "Cross-compiling pake-ffi for arm64-v8a, armeabi-v7a and x86_64"
cargo ndk --platform 23 -t arm64-v8a -t armeabi-v7a -t x86_64 -o "$JNILIBS" build --release --locked -p pake-ffi

# uniffi-bindgen reads metadata the release profile strips, so bindings come from a host debug build.
echo "Building host library for bindgen"
cargo build --locked -p pake-ffi
TARGET_DIR="$(cargo metadata --no-deps --format-version 1 2>/dev/null | python3 -c 'import json, sys; print(json.load(sys.stdin)["target_directory"])')"
HOST_LIB="$TARGET_DIR/debug/librelaypony_pake_ffi.dylib"
[[ -f "$HOST_LIB" ]] || HOST_LIB="$TARGET_DIR/debug/librelaypony_pake_ffi.so"
[[ -f "$HOST_LIB" ]] || { echo "Host library not found in $TARGET_DIR/debug"; exit 1; }

BINDINGS_OUT="$(mktemp -d)"
cargo run -q --locked -p pake-ffi --features cli --bin uniffi-bindgen -- \
  generate --library "$HOST_LIB" --language kotlin --no-format --out-dir "$BINDINGS_OUT"

popd >/dev/null

KOTLIN_OUT="$REPO/pake/src/main/kotlin"
rm -rf "$KOTLIN_OUT/uniffi"
mkdir -p "$KOTLIN_OUT"
cp -r "$BINDINGS_OUT/uniffi" "$KOTLIN_OUT/"

echo "Done. Produced:"
for abi in arm64-v8a armeabi-v7a x86_64; do echo "  $JNILIBS/$abi/librelaypony_pake_ffi.so"; done
echo "  $KOTLIN_OUT/uniffi/relaypony_pake_ffi/relaypony_pake_ffi.kt"
