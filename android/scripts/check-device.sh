#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_status=0
adb logcat -c
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=za.co.cttrains.app.UpgradeFixtureTest --no-daemon || test_status=$?
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
adb logcat -d -b crash > app/build/outputs/ui-screenshots/crash-logcat.txt || true
if grep -E 'Process: za.co.cttrains.app(,|$)|ANR in za.co.cttrains.app( |$)' app/build/outputs/ui-screenshots/crash-logcat.txt; then
  test_status=1
fi
exit "$test_status"
