#!/usr/bin/env bash
# CT Trains Lab: build the experimental edition and run only its UI tests on the connected emulator.
set -euo pipefail
cd "$(dirname "$0")/.."
export CTTRAINS_EXPERIMENTAL=true
test_status=0
adb shell settings put global hide_error_dialogs 1 || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=za.co.cttrains.app.lab --no-daemon || test_status=$?
adb shell settings put system font_scale 1.0 || true
mkdir -p app/build/outputs/ui-screenshots
adb pull /data/local/tmp/cttrains-ui-screenshots/. app/build/outputs/ui-screenshots/ || true
if [ "$test_status" -ne 0 ]; then
  python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
for report in Path("app/build/outputs/androidTest-results").rglob("TEST-*.xml"):
    try:
        for case in ET.parse(report).getroot().iter("testcase"):
            for failure in list(case.findall("failure")) + list(case.findall("error")):
                print(case.get("classname"), case.get("name"), failure.get("message"), (failure.text or "")[:3000])
    except (ET.ParseError, OSError) as error:
        print(report, error)
PY
  adb logcat -d -t 400 '*:W' || true
fi
exit "$test_status"
