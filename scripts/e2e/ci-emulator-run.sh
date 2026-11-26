#!/usr/bin/env bash
set -euo pipefail
API_LEVEL="${1:?api level}"; TIERS="${2:-all}"; ALLOW_MISSING="${3:-true}"
OUT_DIR="e2e-reports/api-${API_LEVEL}"
mkdir -p "$OUT_DIR"
trap 'adb logcat -d -v threadtime > "$OUT_DIR/logcat-full.txt" 2>/dev/null || true' EXIT

adb wait-for-device
if ! adb install -r -t -g artifacts/app-x86_64-debug.apk; then
  adb install -r -t artifacts/app-x86_64-debug.apk
fi
SDK_LEVEL="$(adb shell getprop ro.build.version.sdk | tr -d '\r[:space:]')"
if [ "${SDK_LEVEL:-0}" -ge 33 ]; then
  adb shell pm grant com.pocketlinux android.permission.POST_NOTIFICATIONS || true
fi

if ! adb shell dumpsys package com.pocketlinux | grep -q "AutomationReceiver"; then
  echo "::error::M4 prerequisite missing: AutomationReceiver not registered in com.pocketlinux manifest"
  exit 1
fi

bash scripts/e2e/run-e2e.sh \
  --apk artifacts/app-x86_64-debug.apk \
  --arm64-apk artifacts/app-arm64-debug.apk \
  --abi x86_64 \
  --tier "${TIERS}" \
  --out-dir "$OUT_DIR" \
  --skip-install

ANDROID_TEST_FILES="$(find app/src/androidTest -type f \( -name '*.kt' -o -name '*.java' \) 2>/dev/null || true)"
if [ -z "$ANDROID_TEST_FILES" ]; then
  if [ "${ALLOW_MISSING}" = "true" ]; then
    echo "::warning::No test files in app/src/androidTest/; skipping am instrument until E2E-2 lands."
  else
    echo "::error::No test files found in app/src/androidTest/."
    exit 1
  fi
else
  adb install -r -t artifacts/app-x86_64-debug-androidTest.apk
  adb shell am instrument -w -r com.pocketlinux.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT_DIR/instrumentation.log"
  if grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed" "$OUT_DIR/instrumentation.log"; then
    echo "::error::Instrumentation tests failed."
    exit 1
  fi
  if grep -q "OK (0 tests)" "$OUT_DIR/instrumentation.log" || ! grep -qE "OK \([1-9][0-9]* tests?\)" "$OUT_DIR/instrumentation.log"; then
    echo "::error::Instrumentation did not execute >= 1 passing test."
    exit 1
  fi
fi
