#!/usr/bin/env bash
# Runs inside the emulator (reactivecircus/android-emulator-runner).
set -euo pipefail
PKG=com.cashflow.app
APK=${1:-apk/app-preview.apk}

# The Google search app (Velvet) sometimes crashes natively on the emulator image while booting; monkey aborts
# on ANY app crash ("Monkey aborted due to error", 0 events), so keep that app out of the way.
adb shell pm disable-user --user 0 com.google.android.googlequicksearchbox || true

adb install -r "$APK"

PID=""
CRASH=""
for attempt in 1 2 3; do
  adb shell am force-stop "$PKG" || true
  adb logcat -c
  OUT=$(adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 2>&1 || true)
  echo "$OUT" | grep -E "Events injected|aborted" || true
  sleep 6
  PID=$(adb shell pidof "$PKG" || true)
  echo "attempt $attempt: pid after 6 s: ${PID:-none}"
  # Only real crashes: "FATAL EXCEPTION" blocks and error-level AndroidRuntime lines (the monkey tool logs
  # harmless D/I AndroidRuntime lines that mention the package).
  CRASH=$(adb logcat -d | grep -E "FATAL EXCEPTION| E AndroidRuntime" -A4 | grep "$PKG" || true)
  if [ -n "$PID" ] && [ -z "$CRASH" ]; then break; fi
done
if [ -z "$PID" ] || [ -n "$CRASH" ]; then
  echo "LAUNCH FAILED (process alive: ${PID:+yes}${PID:-no})"
  adb logcat -d | grep -E "FATAL EXCEPTION| E AndroidRuntime|$PKG" | tail -60
  exit 1
fi
echo "LAUNCH OK: $PKG is running, no crash in logcat"

# Best effort: screenshots of one configuration (dark theme, normal font). A failure here never fails the build;
# the gate is the launch check above.
timeout 900 python3 scripts/smoke.py screens || echo "SMOKE (best effort) did not finish cleanly: not a build failure"
