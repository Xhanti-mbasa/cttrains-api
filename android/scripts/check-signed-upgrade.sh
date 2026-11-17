#!/usr/bin/env bash
# Uses an isolated emulator and the exact old and new production-signed APKs.
set -euo pipefail
cd "$(dirname "$0")/.."
baseline_tag=$(jq -r .baselineRelease release-compatibility.json)
baseline_apk=$(jq -r .apk release-compatibility.json)
old="$RUNNER_TEMP/cttrains-previous-production.apk"
curl --fail --location --retry 2 --max-time 180 "https://github.com/$GITHUB_REPOSITORY/releases/download/$baseline_tag/$baseline_apk" --output "$old"
python3 - "$old" <<'CHECKSUM'
import hashlib,json,pathlib,sys
expected=json.loads(pathlib.Path("release-compatibility.json").read_text())
with open(sys.argv[1],"rb") as f:
    assert hashlib.file_digest(f,"sha256").hexdigest()==expected["sha256"],"Baseline APK checksum differs"
CHECKSUM
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose "$old"
adb install "$old"
adb install app/build/outputs/apk/androidTest/production-upgrade-test.apk
run_fixture() {
  adb shell am instrument -w -e class za.co.cttrains.app.UpgradeFixtureTest -e upgradePhase "$1" za.co.cttrains.app.test/androidx.test.runner.AndroidJUnitRunner | tee "$RUNNER_TEMP/cttrains-production-upgrade-$1.txt"
  grep -q 'OK (1 test)' "$RUNNER_TEMP/cttrains-production-upgrade-$1.txt"
}
run_fixture seed
adb install -r app/build/outputs/apk/release/app-release.apk
run_fixture verify
adb logcat -c
adb shell am start -W -n za.co.cttrains.app/.MainActivity
adb shell uiautomator dump /sdcard/cttrains-promoted.xml
adb pull /sdcard/cttrains-promoted.xml "$RUNNER_TEMP/cttrains-promoted.xml"
python3 - "$RUNNER_TEMP/cttrains-promoted.xml" <<'LAUNCHER'
import sys,xml.etree.ElementTree as ET
texts={n.get("text") for n in ET.parse(sys.argv[1]).iter("node")}
assert {"Now","Plan","Mine"}<=texts,"Production launcher did not show the promoted navigation"
LAUNCHER
adb logcat -d -b crash > "$RUNNER_TEMP/cttrains-production-crash.txt"
if grep -E 'Process: za.co.cttrains.app(,|$)' "$RUNNER_TEMP/cttrains-production-crash.txt"; then exit 1; fi
