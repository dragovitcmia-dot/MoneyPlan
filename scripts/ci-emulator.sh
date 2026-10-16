#!/usr/bin/env bash
# Runs inside the emulator (reactivecircus/android-emulator-runner).
set -euo pipefail
PKG=com.cashflow.app
APK=${1:-apk/app-preview.apk}

# The Google search app (Velvet) sometimes crashes natively on the emulator image while booting; monkey aborts
# on ANY app crash ("Monkey aborted due to error", 0 events), so keep that app out of the way.
adb shell pm disable-user --user 0 com.google.android.googlequicksearchbox || true

adb install -r "$APK"
# Android's own notification-permission box must never cover the app during the walks (smoke.py also grants it after each pm clear).
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

PID=""
CRASH=""
for attempt in 1 2 3; do
  adb shell am force-stop "$PKG" || true
  adb logcat -c
  OUT=$(adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 2>&1 || true)
  echo "$OUT" | grep -E "Events injected|aborted" || true
  # Poll every 500 ms for the process (up to 15 s), then let it settle for 4 s and look again: no fixed wait on a slow emulator.
  PID=""
  for _ in $(seq 1 30); do
    PID=$(adb shell pidof "$PKG" || true)
    [ -n "$PID" ] && break
    sleep 0.5
  done
  [ -n "$PID" ] && sleep 4 && PID=$(adb shell pidof "$PKG" || true)
  echo "attempt $attempt: pid after launch: ${PID:-none}"
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

# Gate: measured geometry (bottom navigation targets, wizard buttons inside the screen). Fix the app, not the check.
python3 scripts/geometry.py

# Best effort: screenshots of one configuration (dark theme, normal font). A failure here never fails the build;
# the gate is the launch check above.
timeout 4500 python3 scripts/smoke.py screens || echo "SMOKE (best effort) did not finish cleanly: not a build failure"
