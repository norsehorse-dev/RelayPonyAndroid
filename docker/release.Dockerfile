# Canonical Linux release-build environment for RelayPony (Android), adapted from
# PassPonyAndroid's docker/release.Dockerfile. 4.0 ships a native library (RelayPonyPake, the
# word-code pairing core) for the first time, so releases now follow the native-core path: build
# on Linux, the same host class as F-Droid's buildserver, never directly on macOS. The NDK ships a
# separate prebuilt toolchain per host OS, and a Mac-built .so is not guaranteed to match a
# Linux-built one byte for byte.
#
# Keep NDK_VERSION in step with gradle.properties' ndkVersion by hand.
#
# --platform=linux/amd64 is pinned: the NDK has no linux-aarch64 toolchain, so on an Apple Silicon
# Mac the whole image runs emulated rather than mixing an arm64 rootfs with x86_64 binaries.
FROM --platform=linux/amd64 ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/usr/local/lib/android/sdk
ENV ANDROID_SDK_ROOT=/usr/local/lib/android/sdk
ENV NDK_VERSION=27.2.12479018
ENV ANDROID_NDK_HOME=${ANDROID_SDK_ROOT}/ndk/${NDK_VERSION}
ENV CARGO_HOME=/root/.cargo
ENV PATH=${CARGO_HOME}/bin:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl unzip git perl make python3 ca-certificates openjdk-17-jdk-headless \
      build-essential pkg-config libssl-dev \
    && rm -rf /var/lib/apt/lists/*

# stable is only for building cargo-ndk itself. The RelayPonyPake build runs from inside its
# checkout, where rust-toolchain.toml selects the pinned toolchain and rustup installs it.
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
RUN rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
RUN cargo install cargo-ndk@4.1.2 --locked

# If this URL starts 404ing, https://developer.android.com/studio#command-tools has the current
# commandlinetools-linux-*.zip name; only the build number changes.
RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && curl -sL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip" \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install "platform-tools" "ndk;${NDK_VERSION}"

COPY release-entrypoint.sh /usr/local/bin/release-entrypoint.sh
RUN chmod +x /usr/local/bin/release-entrypoint.sh

WORKDIR /work
ENTRYPOINT ["/usr/local/bin/release-entrypoint.sh"]
