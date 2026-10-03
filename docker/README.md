# Release build container

Builds the signed release APK on Linux, the same host class as F-Droid's buildserver, instead of
on macOS. Starting with 4.0, RelayPony ships a native library (RelayPonyPake, for word-code
pairing), so releases follow the same path as PassPony: the NDK ships a separate prebuilt
toolchain per host OS, and only a Linux build can be expected to match F-Droid's byte for byte.

Run it on the machine that holds the release keystore. The keystore is bind-mounted read-only and
never baked into the image.

Build the image once, and again whenever `gradle.properties`' `ndkVersion` or RelayPonyPake's
pinned Rust toolchain changes:

```
cd ~/Documents/GitHub/RelayPonyAndroid
docker build -t relaypony-release -f docker/release.Dockerfile docker
```

Run a release. The container clones `<ref>` fresh from GitHub with its submodules, so `<ref>` (a
commit SHA, `main`, or the tag) must already be pushed:

```
docker run --rm \
  -v ~/Keys/RelayPony/release.keystore:/keystore/release.keystore:ro \
  -v ~/Documents/GitHub/RelayPonyAndroid/keystore.properties:/keystore-props/keystore.properties:ro \
  -v ~/Documents/GitHub/RelayPonyAndroid:/out \
  relaypony-release <version> <ref>
```

Adjust the first two `-v` paths to where your keystore and `keystore.properties` live. Results land
at `release-<version>/` under the repo root, ready for `tools/verify_repro.sh compare` and the
GitHub release.
