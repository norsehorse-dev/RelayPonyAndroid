#!/usr/bin/env bash
# Runs inside the release.Dockerfile image. Not meant to be run directly
# on a host -- see docker/README.md for the docker run invocation.
set -euo pipefail

VERSION="${1:?usage: release-entrypoint.sh <version> <ref>, e.g. 1.0.0 v1.0.0}"
REF="${2:?usage: release-entrypoint.sh <version> <ref>, e.g. 1.0.0 v1.0.0}"

# REF can be a tag, a branch, or a raw commit SHA -- RELEASE_CHECKLIST.md
# step 3 builds the dry-run release from the just-pushed commit, before
# step 5 creates the tag, so this can't assume a tag already exists.

[[ -f /keystore/release.keystore ]] || {
  echo "Expected the release keystore bind-mounted read-only at /keystore/release.keystore" >&2
  exit 1
}
[[ -f /keystore-props/keystore.properties ]] || {
  echo "Expected keystore.properties bind-mounted read-only at /keystore-props/keystore.properties" >&2
  exit 1
}

echo "--- Cloning RelayPonyAndroid at $REF ---"
git clone https://github.com/norsehorse-dev/RelayPonyAndroid.git /work/repo
cd /work/repo
git checkout "$REF"
git submodule update --init --recursive

# keystore.properties as it exists on the host points storeFile at a host
# path (e.g. /Users/kevinstewart/Keys/RelayPony/release.keystore) that
# doesn't exist inside this container. Rewrite just that one line to the
# container-internal mount point; storePassword/keyAlias/keyPassword pass
# through untouched, exactly as they are on the host, never typed or
# generated in here.
sed 's#^storeFile=.*#storeFile=/keystore/release.keystore#' \
  /keystore-props/keystore.properties > keystore.properties

# Bound parallelism and heap under amd64 emulation on Apple Silicon (PassPony saw an unbounded
# Rust compile deadlock and R8 hang there). These change only scheduling and memory, never the
# output bytes. An explicit -e override on docker run still wins.
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-2}"
export GRADLE_OPTS="${GRADLE_OPTS:--Dorg.gradle.jvmargs=-Xmx6g -Dorg.gradle.workers.max=2 -Dorg.gradle.parallel=false}"

echo "--- Running scripts/release.sh $VERSION (CARGO_BUILD_JOBS=$CARGO_BUILD_JOBS) ---"
bash scripts/release.sh "$VERSION"

echo "--- Copying results to /out ---"
cp -r "release-$VERSION" /out/
echo "Done. Results at release-$VERSION/ on the host."
