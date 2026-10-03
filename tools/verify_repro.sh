#!/usr/bin/env bash
# Reproducible-build gate for RelayPonyAndroid (adapted from PassPonyAndroid), per NorseHorse's Reproducible
# Builds Playbook (docs/REPRODUCIBLE.md links the full playbook). Never
# extracts an APK to the filesystem to compare it (R4 in the playbook):
# AGP's shortened resource names collide case-insensitively (e.g.
# res/IN.xml vs res/In.xml), and extracting on a case-insensitive
# filesystem -- macOS included -- silently drops one of each pair, which
# corrupts any filesystem-based diff without you noticing. Every
# comparison here reads both ZIPs' entries directly via Python's zipfile
# module, in memory, and hashes them.
#
# Usage:
#   tools/verify_repro.sh rebuild <tag> [candidate.apk]
#   tools/verify_repro.sh compare <a.apk> <b.apk>
#   tools/verify_repro.sh content-hash <apk>
#
# rebuild:  clones <tag> twice into isolated roots (separate
#           GRADLE_USER_HOME, --no-daemon), builds each with $GRADLE_TASK,
#           fails unless the two builds are content-identical, then fails
#           unless the optional candidate APK also matches them. Prints
#           per-dex SHA-256s and any embedded R8 marker. Requires network
#           (clones the repo with its submodules) and an Android SDK/NDK
#           matching gradle.properties' ndkVersion.
# compare:  content comparison of two APKs via a per-entry SHA-256
#           manifest, excluding only the signature files
#           (META-INF/*.SF|*.RSA|*.DSA|*.EC|MANIFEST.MF). Prints IDENTICAL
#           or a per-file diff, and exits nonzero on any difference.
# content-hash: SHA-256 of the sorted per-entry manifest (same exclusions)
#           -- the "content hash" to publish in release notes. Unlike a
#           whole-file hash it does not change with the signature, so
#           anyone rebuilding from source can reproduce it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_URL="${REPO_URL:-$(git -C "$REPO_ROOT" remote get-url origin 2>/dev/null || true)}"
GRADLE_TASK="${GRADLE_TASK:-:app:assembleRelease}"
APK_REL_PATH="${APK_REL_PATH:-app/build/outputs/apk/release/app-release.apk}"

# Excludes exactly the files the signing step touches -- everything else is
# covered by F-Droid's own verification (see docs/REPRODUCIBLE.md, "How
# F-Droid's verification actually works").
SIG_FILE_RE='^META-INF/([^/]+\.(SF|RSA|DSA|EC)|MANIFEST\.MF)$'

resolve_java_home() {
  # Deliberately only trusts an explicit, caller-exported JAVA_HOME -- an
  # earlier version of this function also fell back to reading
  # org.gradle.java.home out of the real (pre-isolation) gradle.properties,
  # on the theory that whatever JDK a developer's day-to-day builds
  # resolve to was a safe bet for a `jvmToolchain(17)`
  # requirement too. That's wrong whenever the machine's default JDK
  # isn't actually 17 itself (a JDK N can compile *targeting* bytecode 17
  # via sourceCompatibility/targetCompatibility without being a JDK 17
  # runtime, but an explicit jvmToolchain(17) call requires the real
  # thing) -- it silently pointed at the wrong major version instead of
  # surfacing the problem. Rather than guess, this only acts on a JAVA_HOME
  # the caller set on purpose; every other case is left to the
  # foojay-resolver-convention plugin (settings.gradle.kts) to auto-
  # provision a real JDK 17, or to Gradle's own auto-detection if a JDK 17
  # happens to already be installed somewhere it looks.
  if [[ -n "${JAVA_HOME:-}" ]]; then
    printf '%s' "$JAVA_HOME"
  fi
}

manifest_py() {
  # $1 = apk path. Prints "name\tsha256", one per non-signature entry,
  # sorted by name. Never writes entry contents to disk.
  python3 - "$1" "$SIG_FILE_RE" <<'PY'
import sys, zipfile, hashlib, re
apk, sig_re = sys.argv[1], re.compile(sys.argv[2])
with zipfile.ZipFile(apk) as z:
    rows = []
    for info in z.infolist():
        if info.is_dir() or sig_re.match(info.filename):
            continue
        rows.append((info.filename, hashlib.sha256(z.read(info.filename)).hexdigest()))
rows.sort()
for name, digest in rows:
    print(f"{name}\t{digest}")
PY
}

content_hash() {
  manifest_py "$1" | python3 -c "import sys, hashlib; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())"
}

print_dex_info() {
  # $1 = apk path, $2 = label for the printout (e.g. "A" or "candidate").
  python3 - "$1" "$2" <<'PY'
import sys, zipfile, hashlib, re
apk, label = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk) as z:
    for name in sorted(n for n in z.namelist() if re.match(r'^classes\d*\.dex$', n)):
        data = z.read(name)
        digest = hashlib.sha256(data).hexdigest()
        m = re.search(rb'~~R8\{[^}]*\}', data)
        marker = m.group(0).decode('utf-8', 'replace') if m else '(no R8 marker found)'
        print(f"  {label} {name}: sha256={digest}")
        print(f"    {marker}")
PY
}

cmd_content_hash() {
  local apk="${1:?usage: verify_repro.sh content-hash <apk>}"
  content_hash "$apk"
}

cmd_compare() {
  local a="${1:?usage: verify_repro.sh compare <a.apk> <b.apk>}"
  local b="${2:?usage: verify_repro.sh compare <a.apk> <b.apk>}"
  python3 - "$a" "$b" "$SIG_FILE_RE" <<'PY'
import sys, zipfile, hashlib, re
a_path, b_path, sig_re = sys.argv[1], sys.argv[2], re.compile(sys.argv[3])

def manifest(path):
    with zipfile.ZipFile(path) as z:
        out = {}
        for info in z.infolist():
            if info.is_dir() or sig_re.match(info.filename):
                continue
            out[info.filename] = hashlib.sha256(z.read(info.filename)).hexdigest()
        return out

ma, mb = manifest(a_path), manifest(b_path)
only_a = sorted(set(ma) - set(mb))
only_b = sorted(set(mb) - set(ma))
diff = sorted(n for n in (set(ma) & set(mb)) if ma[n] != mb[n])

if not only_a and not only_b and not diff:
    print("IDENTICAL")
    sys.exit(0)

print("DIFFERS")
for n in only_a:
    print(f"  only in A: {n}")
for n in only_b:
    print(f"  only in B: {n}")
for n in diff:
    print(f"  differs:   {n}")
sys.exit(1)
PY
}

cmd_rebuild() {
  local tag="${1:?usage: verify_repro.sh rebuild <tag> [candidate.apk]}"
  local candidate="${2:-}"
  [[ -n "$REPO_URL" ]] || { echo "REPO_URL not set and no 'origin' remote found" >&2; exit 1; }
  if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]]; then
    # AGP needs one or the other -- local.properties' sdk.dir is the usual
    # source, but it's gitignored (per-machine, never committed) so a
    # fresh clone never has it; only an env var survives into one. Failing
    # here, before either clone/build even starts, beats a Gradle stack
    # trace ~40s into the second of two builds. Your own working copy's
    # local.properties already has the right path for this machine:
    #   export ANDROID_HOME=$(sed -n 's/^sdk.dir=//p' local.properties)
    echo "ANDROID_HOME (or ANDROID_SDK_ROOT) is not set -- export one before running rebuild." >&2
    exit 1
  fi

  local work
  work="$(mktemp -d "${TMPDIR:-/tmp}/verify-repro.XXXXXX")"
  echo "Work directory: $work"

  local java_home
  java_home="$(resolve_java_home)"
  if [[ -n "$java_home" ]]; then
    echo "Using org.gradle.java.home=$java_home for both builds"
  fi

  local root
  for root in srcA srcB; do
    echo "--- Cloning $tag into $root ---"
    git clone --quiet --branch "$tag" --depth 1 "$REPO_URL" "$work/$root"
    # RelayPonyPake, AgePonyAndroid and PonyDirect-Kotlin come in here, pinned by the tag's submodules.
    git -C "$work/$root" submodule update --init --recursive --quiet
  done

  for root in srcA srcB; do
    echo "--- Building $root ($GRADLE_TASK) ---"
    if [[ -f "$work/$root/scripts/build-pake.sh" ]]; then
      bash "$work/$root/scripts/build-pake.sh"
    fi
    local gradle_opts=()
    [[ -n "$java_home" ]] && gradle_opts+=("-Dorg.gradle.java.home=$java_home")
    ( cd "$work/$root" && env GRADLE_USER_HOME="$work/gradle-$root" ./gradlew --no-daemon "${gradle_opts[@]}" "$GRADLE_TASK" )
  done

  local apk_a="$work/srcA/$APK_REL_PATH"
  local apk_b="$work/srcB/$APK_REL_PATH"
  [[ -f "$apk_a" ]] || { echo "Build A did not produce $APK_REL_PATH" >&2; exit 1; }
  [[ -f "$apk_b" ]] || { echo "Build B did not produce $APK_REL_PATH" >&2; exit 1; }

  echo "--- Dex info ---"
  print_dex_info "$apk_a" "A"
  print_dex_info "$apk_b" "B"

  echo "--- Comparing A vs B ---"
  if cmd_compare "$apk_a" "$apk_b"; then
    echo "Two clean builds of $tag are content-identical."
  else
    echo "Two clean builds of $tag DIFFER -- not reproducible." >&2
    exit 1
  fi

  local hash
  hash="$(content_hash "$apk_a")"
  echo "Content hash: $hash"
  echo "buildA APK: $apk_a"

  if [[ -n "$candidate" ]]; then
    echo "--- Comparing candidate ($candidate) vs buildA ---"
    if cmd_compare "$candidate" "$apk_a"; then
      echo "Candidate matches the clean-clone build."
    else
      echo "Candidate DOES NOT match the clean-clone build." >&2
      exit 1
    fi
  fi
}

main() {
  local sub="${1:-}"
  [[ $# -gt 0 ]] && shift
  case "$sub" in
    rebuild) cmd_rebuild "$@" ;;
    compare) cmd_compare "$@" ;;
    content-hash) cmd_content_hash "$@" ;;
    *)
      echo "usage: $(basename "$0") rebuild <tag> [candidate.apk] | compare <a.apk> <b.apk> | content-hash <apk>" >&2
      exit 1
      ;;
  esac
}

main "$@"
