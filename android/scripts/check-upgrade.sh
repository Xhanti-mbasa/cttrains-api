#!/usr/bin/env bash
# Install a baseline production-identity build, seed encrypted data, upgrade, verify.
set -euo pipefail
root=$(git rev-parse --show-toplevel)
baseline="$RUNNER_TEMP/cttrains-upgrade-baseline"
git worktree add --detach "$baseline" d4b4d7eed5606efb101953b276bcb99cdf760283
trap 'git -C "$root" worktree remove --force "$baseline"' EXIT
cp "$root/android/app/src/androidTest/kotlin/za/co/cttrains/app/UpgradeFixtureTest.kt" "$baseline/android/app/src/androidTest/kotlin/za/co/cttrains/app/"
(cd "$baseline/android" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon)
adb install "$baseline/android/app/build/outputs/apk/debug/app-debug.apk"
adb install "$baseline/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
run_fixture() {
  adb shell am instrument -w -e class za.co.cttrains.app.UpgradeFixtureTest -e upgradePhase "$1" za.co.cttrains.app.test/androidx.test.runner.AndroidJUnitRunner | tee "$RUNNER_TEMP/cttrains-upgrade-$1.txt"
  grep -q 'OK (1 test)' "$RUNNER_TEMP/cttrains-upgrade-$1.txt"
}
run_fixture seed
(cd "$root/android" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon)
adb install -r "$root/android/app/build/outputs/apk/debug/app-debug.apk"
adb install -r "$root/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
run_fixture verify
adb uninstall za.co.cttrains.app.test
adb uninstall za.co.cttrains.app
