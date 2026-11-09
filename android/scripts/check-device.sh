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
# Keep failure evidence available in the job log even when artifact quota is full.
if [ "$test_status" -ne 0 ]; then
  python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
for report in Path("app/build/outputs/androidTest-results").rglob("TEST-*.xml"):
    try:
        root = ET.parse(report).getroot()
        for case in root.iter("testcase"):
            for failure in list(case.findall("failure")) + list(case.findall("error")):
                print(case.get("classname"), case.get("name"), failure.get("message"), failure.text)
    except (ET.ParseError, OSError) as error:
        print(report, error)
PY
  adb logcat -d -t 500 '*:W' || true
fi
exit "$test_status"
