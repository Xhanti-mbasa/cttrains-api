# CT Trains for Android

A native Kotlin Android app with **Jetpack Compose and Google Material 3**. Search the station-to-station journeys available at [cttrains.co.za](https://cttrains.co.za/train-form.php), including transfers and all intermediate stops.

The app lives in `android/` alongside the existing Node.js API. It accesses the website directly over HTTPS: **no backend deployment, API key or account is required**. The existing API and MCP server are unchanged.

## Features

- Searchable station selection; swap origin and destination.
- Material date picker and accessible 24-hour departure selection in the website's 15-minute intervals.
- Native journey cards with every leg, train number, connection time and expandable stops.
- Fare information when included in the website's response; no prices are hardcoded.
- End-to-end duration includes transfer waits; fastest journeys are marked.
- Save up to 20 frequently used routes; last-used stations and time are restored.
- The last 12 searches are cached for up to 14 days. On refresh failure, only an exact route/date/time match is used, with an explicit saved-timetable notice and fetch timestamp.
- Share complete journeys using the Android share sheet.
- System/light/dark themes, optional Material You device colours on Android 12+, and tablet layouts.
- No location permission, ads, analytics SDK or sign-in. Search parameters and the device IP go to CT Trains.

## Open and build

Requirements: Android Studio supporting AGP 8.9, JDK 17, Android SDK 35 and Build Tools 35.0.0. Minimum supported device: Android 8.0 (API 26).

1. Clone this branch, then open **the `android/` directory** in Android Studio.
2. Let Gradle sync and install the requested SDK packages.
3. Run the `app` configuration on a phone or emulator.

From a terminal with `ANDROID_HOME` configured:

```bash
cd android
./gradlew :core:test :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is a development build signed by a debug key. For distribution, use Android Studio's **Generate Signed Bundle / APK** with your own release key; keep that key outside the repository. A Play Store release also needs current target API and publishing requirements reviewed before submission.

## Verification

The `core` JVM tests cover captured live HTML for direct and transfer journeys, station extraction, missing/changed markup, no-result handling, date ranges, Sundays/advertised holidays, time intervals, cache retention/deduplication, serialization and sharing. Timetable fixtures were retrieved on **1 October 2026** and contain source timetable content without advertising scripts.

```bash
./gradlew :core:test
./gradlew :app:lintDebug :app:assembleDebug
```

The repository's **Android build and release** workflow runs a clean build, all JVM tests, debug and release lint, and both APK variants. It verifies the release APK signature and checks that it is not debuggable. It uploads the APK, SHA-256 checksum and reports. Pull requests run these checks without publishing or accessing production signing secrets.

## GitHub Releases

After this PR is merged, changes to `android/` on `main` automatically create a GitHub prerelease with the installable APK and checksum in the repository's **Releases** section. Each automatic build gets its own `android-v1.0.0-build.N` tag, pointing at the exact source commit.

For a named version, push a tag or use **Actions → Android build and release → Run workflow** on `main`, and enter a tag such as `android-v1.0.1`:

```bash
git checkout main
git pull
git tag android-v1.0.1
git push origin android-v1.0.1
```

`android/version.properties` stores the default app version. Named tags set the APK's version name; the workflow adds its run number to the base version code. The About screen displays the built version. Failed builds never publish a release. Publishing stages a draft until both assets are uploaded, and a rerun leaves an already published release unchanged.

### Signing

With no signing secrets configured, the workflow explicitly opts into **development signing** using an Android debug key. These release-variant APKs are installable and not debuggable, but are marked **prereleases** and labelled `development`. The key may change between workflow runs. If Android rejects an update due to a signature mismatch, uninstall the previous development build before installing the new one; this also removes its locally saved data.

For stable production signing, add all four repository **Actions secrets** under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `CTTRAINS_KEYSTORE_BASE64` | Single-line Base64 of your private release keystore |
| `CTTRAINS_KEYSTORE_PASSWORD` | Keystore password |
| `CTTRAINS_KEY_ALIAS` | Signing key alias |
| `CTTRAINS_KEY_PASSWORD` | Signing key password |

Use the same private key for every production version. Keep it backed up privately and outside Git. Partial signing configuration fails explicitly. The decoded key is temporary, never included in artifacts, and removed after the build. A stable tag such as `android-v1.0.1` with all four secrets configured publishes a normal release; development signatures and tags with a suffix remain prereleases. The workflow does not publish to the Play Store.

## Data integration

- Station list and holiday policy: `GET https://cttrains.co.za/train-form.php`
- Schedules: `GET https://cttrains.co.za/train-schedule.php` with `fromStation`, `toStation`, `travelDate=dd/MM/yyyy`, and `departureTime=HH:mm`.
- The station list bundled with the app is a fallback; launch refreshes it from the website and persists successful refreshes.
- Requests are user-triggered, cancellable and time-bounded. Changing form selections cancels the in-flight search so old results cannot populate a different route.
- The parser preserves separate train legs and verifies connection station names. Unexpected source markup produces a visible error rather than silently claiming there are no services.
- Departures before the requested time are filtered out: the source can wrap to earlier trains when there are no later departures, without clearly indicating a different travel date.
- Date/time interpretation uses `Africa/Johannesburg`, even if the device is in another timezone.
- The finder restricts travel dates to today through the end of next month. Sundays and dates in the source's holiday list are rejected. Holiday coverage depends on the source list being maintained.
- Timetables may change and are **not live train tracking**. Platform details are not guessed; confirm them at the station.

## Structure

```text
app/      Android UI, ViewModel, cancellable HTTPS access and DataStore persistence
core/     Kotlin models, HTML parser, calendar rules, cache policy and JVM tests
```

This is an independent community application, not affiliated with CT Trains, PRASA or Metrorail. The app uses the repository's existing licence.
