#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_status=0
# CI emulators often raise "System UI isn't responding", which covers the app and swallows test taps.
adb shell settings put global hide_error_dialogs 1 || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
./gradlew connectedDebugAndroidTest --no-daemon || test_status=$?
# Copy while emulator-runner still has the device connected, including failures.
mkdir -p app/build/outputs/ui-screenshots
adb pull /data/local/tmp/cttrains-ui-screenshots/. app/build/outputs/ui-screenshots/ || true
exit "$test_status"
