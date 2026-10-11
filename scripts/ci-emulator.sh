#!/usr/bin/env bash
# Runs inside the emulator (reactivecircus/android-emulator-runner).
set -euo pipefail
PKG=com.cashflow.app
APK=${1:-apk/app-preview.apk}

adb install -r "$APK"
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1
sleep 6

PID=$(adb shell pidof "$PKG" || true)
echo "pid after 6 s: ${PID:-none}"
CRASH=$(adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" -A4 | grep "$PKG" || true)
if [ -z "$PID" ] || [ -n "$CRASH" ]; then
  echo "LAUNCH FAILED (process alive: ${PID:+yes}${PID:-no})"
  adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime|$PKG" | tail -60
  exit 1
fi
echo "LAUNCH OK: $PKG is running, no crash in logcat"

python3 scripts/smoke.py screens
