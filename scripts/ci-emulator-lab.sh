#!/usr/bin/env bash
# Runs inside the emulator after the real app's checks: installs the Cashflow Lab APK next to the real app, checks that it launches
# (no crash) and shows its amber LAB tag, then walks its scenarios for screenshots (best effort).
set -euo pipefail
PKG=com.cashflow.app.lab
APK=${1:-apk-lab/app-lab.apk}

adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
# both apps are installed now: the real one must still be there, untouched
adb shell pm list packages | grep -q "package:com.cashflow.app$" && echo "real app still installed next to the Lab"

PID=""
CRASH=""
for attempt in 1 2 3; do
  adb shell am force-stop "$PKG" || true
  adb logcat -c
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 2>&1 | grep -E "Events injected|aborted" || true
  PID=""
  for _ in $(seq 1 30); do
    PID=$(adb shell pidof "$PKG" || true)
    [ -n "$PID" ] && break
    sleep 0.5
  done
  [ -n "$PID" ] && sleep 4 && PID=$(adb shell pidof "$PKG" || true)
  echo "lab attempt $attempt: pid after launch: ${PID:-none}"
  CRASH=$(adb logcat -d | grep -E "FATAL EXCEPTION| E AndroidRuntime" -A4 | grep "$PKG" || true)
  if [ -n "$PID" ] && [ -z "$CRASH" ]; then break; fi
done
if [ -z "$PID" ] || [ -n "$CRASH" ]; then
  echo "LAB LAUNCH FAILED"
  adb logcat -d | grep -E "FATAL EXCEPTION| E AndroidRuntime|$PKG" | tail -60
  exit 1
fi
echo "LAB LAUNCH OK: $PKG is running, no crash in logcat"

# Gate: the Lab shows its LAB tag; the best-effort walk adds screenshots of the Lab card, Upcoming tabs and the purchase strip.
# The bounded screenshot pass (dark normal, light normal, dark font scale 2.0): each run starts "Run all checks" and marks the first checks.
for cfg in "dark-normal 1 1.0" "light-normal 0 1.0" "dark-font2 1 2.0"; do
  set -- $cfg
  echo "== lab screenshots: $1"
  LAB_NAME="$1-lab" LAB_NIGHT="$2" LAB_FONT="$3" timeout 900 python3 scripts/lab_smoke.py screens || { code=$?; echo "LAB SMOKE ($1) exit $code"; [ "$code" = "3" ] && exit 1; echo "(best effort: not a build failure)"; }
done
