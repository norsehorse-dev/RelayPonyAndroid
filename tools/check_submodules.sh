#!/usr/bin/env bash
# Pre-tag check for submodule pins. Run before tagging a release:  bash tools/check_submodules.sh
#
# A tag builds on F-Droid (and in docker/) from exactly the commits its submodules pin. Issue #3:
# the 3.0 tag pinned PonyDirect-Kotlin to an old commit, the local build silently used a newer
# sibling checkout, and F-Droid's build failed. This fails if any of these hold for any submodule:
#   1. it is not checked out, or its checkout differs from the pinned commit, or has local changes
#   2. the pinned commit is not on its remote yet (a fresh clone could not fetch it)
#   3. a sibling or ~/Apps checkout of it is on a different commit (you may have developed against
#      code the pin doesn't include). Warning only with --allow-sibling-drift.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ALLOW_DRIFT=0
[[ "${1:-}" == "--allow-sibling-drift" ]] && ALLOW_DRIFT=1
cd "$REPO"

fail=0
bad() { echo "FAIL  $*"; fail=1; }
ok() { echo "ok    $*"; }

paths="$(git config -f .gitmodules --get-regexp '^submodule\..*\.path$' | awk '{print $2}')"
[[ -n "$paths" ]] || { echo "No submodules in .gitmodules"; exit 0; }

for path in $paths; do
  pinned="$(git ls-tree HEAD "$path" | awk '$2 == "commit" {print $3}')"
  staged="$(git ls-files -s -- "$path" | awk '$1 == "160000" {print $2}')"
  [[ -n "$pinned" ]] || pinned="$staged"
  if [[ -z "$pinned" ]]; then bad "$path: not a submodule in HEAD or the index"; continue; fi
  if [[ -n "$staged" && "$staged" != "$pinned" ]]; then
    echo "note  $path: index pins $staged (not yet committed; HEAD pins $pinned). Checking the index pin."
    pinned="$staged"
  fi

  if [[ ! -e "$path/.git" ]]; then bad "$path: not checked out (git submodule update --init --recursive)"; continue; fi
  head="$(git -C "$path" rev-parse HEAD)"
  [[ "$head" == "$pinned" ]] || bad "$path: checkout is at $head but the pin is $pinned"
  [[ -z "$(git -C "$path" status --porcelain)" ]] || bad "$path: has uncommitted changes"

  url="$(git config -f .gitmodules --get "submodule.$path.url" || true)"
  if git ls-remote "$url" >/dev/null 2>&1; then
    git -C "$path" fetch --quiet origin 2>/dev/null || true
    if [[ -n "$(git -C "$path" branch -r --contains "$pinned" 2>/dev/null)" ]]; then
      ok "$path: pinned $pinned is on its remote"
    else
      bad "$path: pinned $pinned is not on any remote branch of $url (push it first)"
    fi
  else
    bad "$path: remote $url is not reachable (does the repo exist and is it public?)"
  fi

  name="$(basename "$path")"
  for sib in "$REPO/../$name" "$HOME/Apps/$name"; do
    [[ -d "$sib/.git" || -f "$sib/.git" ]] || continue
    sib="$(cd "$sib" && pwd)"
    [[ "$sib" == "$REPO/$path" ]] && continue
    sibhead="$(git -C "$sib" rev-parse HEAD 2>/dev/null || true)"
    if [[ -n "$sibhead" && "$sibhead" != "$pinned" ]]; then
      msg="$path: sibling checkout $sib is at $sibhead, the pin is $pinned. If you developed against the sibling, bump the pin."
      if [[ "$ALLOW_DRIFT" == 1 ]]; then echo "warn  $msg"; else bad "$msg"; fi
    fi
  done
done

if [[ "$fail" == 0 ]]; then echo "All submodule pins are clean and published."; else echo "Fix the FAIL lines before tagging."; exit 1; fi
