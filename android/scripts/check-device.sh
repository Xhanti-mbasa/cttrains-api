#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_status=0
./gradlew connectedDebugAndroidTest --no-daemon || test_status=$?
# Copy while emulator-runner still has the device connected, including failures.
mkdir -p app/build/outputs/ui-screenshots
adb pull /data/local/tmp/cttrains-ui-screenshots/. app/build/outputs/ui-screenshots/ || true
exit "$test_status"
