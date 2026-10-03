#!/usr/bin/env bash
# Release build driver for RelayPony (Android), adapted from PassPonyAndroid's. Builds a signed
# release APK from the current commit, on the machine holding the real release keystore (normally
# inside docker/release.Dockerfile, see docker/README.md). Stops before anything irreversible: it
# never tags, pushes or publishes, and prints those commands at the end.
#
# Usage: scripts/release.sh <version>   (e.g. scripts/release.sh 4.0.0)
#
# Signing happens inside the one Gradle release build (keystore.properties at the repo root),
# never by re-signing the APK afterwards with apksigner, which rewrites ZIP alignment and breaks
# byte-identity with a from-source rebuild.

set -euo pipefail

VERSION="${1:?usage: scripts/release.sh <version>, e.g. scripts/release.sh 1.0.0}"
TAG="v$VERSION"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO_ROOT/release-$VERSION"

[[ -f "$REPO_ROOT/keystore.properties" ]] || {
  echo "keystore.properties not found at the repo root -- this must run on the machine holding the real release keystore, not a CI runner or a throwaway clone." >&2
  exit 1
}

if ! git -C "$REPO_ROOT" diff --quiet || ! git -C "$REPO_ROOT" diff --cached --quiet; then
  echo "Working tree has uncommitted changes -- commit or stash before releasing." >&2
  exit 1
fi

CURRENT_VERSION_NAME="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$REPO_ROOT/app/build.gradle.kts")"
[[ "$CURRENT_VERSION_NAME" == "$VERSION" ]] || {
  echo "app/build.gradle.kts's versionName is \"$CURRENT_VERSION_NAME\", not \"$VERSION\" -- bump versionName and versionCode first, commit that, then rerun." >&2
  exit 1
}

echo "--- Building release APK ---"
( cd "$REPO_ROOT" && bash scripts/build-pake.sh && ./gradlew --no-daemon :app:assembleRelease )

# The Play AAB is not built here: Play re-signs and re-splits it server-side, so it is neither
# byte-reproducible nor what users run. This produces the APK that F-Droid rebuilds and that
# tools/verify_repro.sh can compare.
FOSS_APK="$REPO_ROOT/app/build/outputs/apk/release/app-release.apk"
[[ -f "$FOSS_APK" ]] || { echo "Expected release APK not found at $FOSS_APK" >&2; exit 1; }

mkdir -p "$OUT"
DEST_APK="$OUT/RelayPony-$VERSION.apk"
cp "$FOSS_APK" "$DEST_APK"

echo "--- Checksums ---"
( cd "$OUT" && shasum -a 256 "$(basename "$DEST_APK")" | tee "RelayPony-$VERSION-SHA256SUMS.txt" )

echo "--- Content hash (see docs/REPRODUCIBLE.md) ---"
CONTENT_HASH="$(bash "$REPO_ROOT/tools/verify_repro.sh" content-hash "$DEST_APK")"
echo "$CONTENT_HASH"

NOTES="$OUT/RELEASE_NOTES-$VERSION.md"
if [[ -f "$REPO_ROOT/RELEASE_NOTES.template.md" ]]; then
  sed -e "s/{{VERSION}}/$VERSION/g" -e "s/{{TAG}}/$TAG/g" -e "s/{{CONTENT_HASH}}/$CONTENT_HASH/g" \
    "$REPO_ROOT/RELEASE_NOTES.template.md" > "$NOTES"
else
  echo "No RELEASE_NOTES.template.md at the repo root -- writing a minimal stub instead." >&2
  {
    echo "# RelayPony (Android) $VERSION"
    echo
    echo "<!-- Fill in: what changed since the last release. Honest changelog, no marketing fluff. -->"
    echo
    echo "## Verification"
    echo
    echo "- Content hash: \`$CONTENT_HASH\`"
    echo "- SHA-256 checksums: see \`RelayPony-$VERSION-SHA256SUMS.txt\`"
    echo "- Reproduce this build yourself: \`tools/verify_repro.sh rebuild $TAG RelayPony-$VERSION.apk\`"
  } > "$NOTES"
fi

echo
echo "Built: $OUT"
ls -la "$OUT"
echo
echo "Nothing has been tagged, pushed, or published. Once you've filled in $NOTES and tested on devices, the remaining steps are yours to run:"
echo
echo "  gpg --detach-sign --armor \"$DEST_APK\""
echo "  git tag $TAG"
echo "  git push origin $TAG"
echo "  gh release create $TAG \"$DEST_APK\" \"$DEST_APK.asc\" \"$OUT/RelayPony-$VERSION-SHA256SUMS.txt\" --title \"$VERSION\" --notes-file \"$NOTES\""
