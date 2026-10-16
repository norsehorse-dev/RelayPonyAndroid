#!/usr/bin/env bash
# Captures raw store screenshots from the debug build on the one connected device or emulator,
# one set per language, into build/screenshots/<play-locale>/. tools/frame-screenshots.py then
# turns them into the framed images under fastlane/metadata/android/<locale>/images/phoneScreenshots.
#
# Usage: tools/play-screenshots.sh [lang ...]      (default: all seven)
#   DEMO_THEME=light tools/play-screenshots.sh     (default: dark)
#   DEVICE_KIND=tablet tools/play-screenshots.sh   (a tablet AVD such as Pixel_Tablet, captured in
#                                                   landscape at its native size, for the 7-inch and
#                                                   10-inch Play slots; default: phone at 1080x1920)
set -euo pipefail

cd "$(dirname "$0")/.."
PKG=com.relaypony.android
ACTIVITY="$PKG/.MainActivity"
KIND="${DEVICE_KIND:-phone}"
OUT=build/screenshots
if [ "$KIND" = "tablet" ]; then OUT=build/screenshots/tablet; fi
THEME="${DEMO_THEME:-dark}"
SCREENS="home sendto transfer received pair word"
LANGS="${*:-en de es fr hi ja pt-BR}"

play_locale() {
  case "$1" in
    en) echo en-US ;; de) echo de-DE ;; es) echo es-ES ;; fr) echo fr-FR ;;
    hi) echo hi-IN ;; ja) echo ja-JP ;; pt-BR) echo pt-BR ;;
    *) echo "unknown language: $1" >&2; exit 1 ;;
  esac
}

devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}' | wc -l | tr -d ' ')
if [ "$devices" != "1" ]; then
  echo "exactly one device must be connected (found $devices)" >&2
  exit 1
fi

./gradlew :app:installDebug -q

cleanup() {
  adb shell wm size reset >/dev/null 2>&1 || true
  adb shell wm density reset >/dev/null 2>&1 || true
  adb shell settings put system accelerometer_rotation 1 >/dev/null 2>&1 || true
  adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null 2>&1 || true
  adb shell settings put global stay_on_while_plugged_in 0 >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ "$KIND" = "tablet" ]; then
  adb shell settings put system accelerometer_rotation 0
  adb shell settings put system user_rotation 1
else
  adb shell wm size 1080x1920
  adb shell wm density 420
fi
adb shell settings put global stay_on_while_plugged_in 7
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true

adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile hide >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e airplane hide >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command status -e bluetooth hide >/dev/null

# Let the app reach its data dir and demo state cleanly.
adb shell pm grant "$PKG" android.permission.CAMERA >/dev/null 2>&1 || true

for lang in $LANGS; do
  locale=$(play_locale "$lang")
  dir="$OUT/$locale"
  mkdir -p "$dir"
  n=1
  for screen in $SCREENS; do
    adb shell am force-stop "$PKG"
    adb shell am start -W -n "$ACTIVITY" --es demo_lang "$lang" --es demo_screen "$screen" --es demo_theme "$THEME" >/dev/null
    sleep 3.5
    file="$dir/${n}_${screen}.png"
    adb exec-out screencap -p > "$file"
    size=$(wc -c < "$file" | tr -d ' ')
    if [ "$size" -lt 40000 ]; then
      echo "capture looks blank: $file ($size bytes)" >&2
      exit 1
    fi
    echo "$file"
    n=$((n + 1))
  done
done

adb shell am force-stop "$PKG"
echo "raw captures in $OUT; now: python3 tools/frame-screenshots.py --platform android"
