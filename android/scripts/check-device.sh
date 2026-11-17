#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_status=0
# Permission-denial tests need a fresh install; directions tests intentionally grant location.
# Keep both sets, with separate installations and reports, so one cannot alter the other's preconditions.
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Run this destructive test suite only on an isolated emulator.' >&2; exit 1; }
adb logcat -c
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=za.co.cttrains.app.lab --no-daemon || test_status=$?
mkdir -p app/build/outputs/ui-screenshots/lab-test-results
cp -R app/build/outputs/androidTest-results/connected/. app/build/outputs/ui-screenshots/lab-test-results/ || true
if [ "$test_status" -eq 0 ]; then
  adb uninstall za.co.cttrains.app.test >/dev/null 2>&1 || true
  adb uninstall za.co.cttrains.app >/dev/null 2>&1 || true
  ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notPackage=za.co.cttrains.app.lab -Pandroid.testInstrumentationRunnerArguments.notClass=za.co.cttrains.app.UpgradeFixtureTest --no-daemon || test_status=$?
fi
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
