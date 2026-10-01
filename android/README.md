# CT Trains — Smart Transit MVP

Native **Kotlin, Jetpack Compose and Google Material 3** transit planner. The app incorporates the first stage of the [smart transit proposal](docs/SMART_TRANSIT_VISION.md), with the [architecture and remaining stages](docs/TRANSIT_ARCHITECTURE.md) documented explicitly.

## What works now

- Map-first home with real positions for all 102 bundled stations, tap/search selection, origin/destination controls and favourite stations.
- Offline station map with pan/zoom. Optional Google Maps street map through Maps Compose, enabled after user opt-in when a key is configured.
- Separate train-leg route connections, transfers, expandable stops, departure/arrival times and source-provided fares with source/fetch date. Connections are not railway track geometry or walking directions.
- Direct CT Trains source and optional normalized backend source. Available-source switching and cache separation prevent incompatible results being combined. Google directions open separately for comparison.
- Room persistence, random local UUID, encrypted personal payloads using Android Keystore and automatic migration of existing DataStore preferences/routes/cache.
- Saved journeys, optional permission-gated app reminders with presets/custom lead, notification tap-through, cancellation and a deterministic leave-by calculation using the user's own access/buffer estimates.
- Calendar export through a user-reviewed Android insert intent/chooser, compatible with Google Calendar when installed. No calendar account or read permission required by the app.
- Optional, cancellable foreground location sample through Fused Location Provider. No continuous GPS polling, raw GPS history or background location permission. The sample is forgotten when the app backgrounds or after five minutes.
- Favourite routes, sharing, dark/light/system themes, optional Material You colours and local data deletion.

## Privacy and limits

No mandatory account, email, analytics, hardware/device identifier or cloud behavioral profile. Profile/preferences, favourite route/station lists, cached search content and saved journey content are AES-GCM encrypted in Room. Public station positions and indexed scheduling timestamps remain readable inside the private database. Device backup/transfer is disabled. Reinstall or resetting the profile creates a new UUID; this app does not try to recognize a device after reinstall.

Search queries and IP addresses go to the selected transit source. Enabling Google street maps uses Google's Maps SDK/services; a location request uses Google Play services. Calendar export hands event information to the calendar app the user chooses, where their calendar account/sync settings apply. No location, health, calendar or behavioral profile is uploaded by the app to its transit backend.

Timetables and station positions are not live service availability. Platforms, delays/cancellations and walking durations are not invented. Reminders use WorkManager and may be delayed by Android battery controls; they are not exact alarms. Saved plans are not treated as observed journeys and are retained for up to 90 days. Calendar events are managed separately from app reminders and local deletion. Health Connect, geofencing, routine learning, weather, calibrated predictions, calendar reading/sync and an LLM are later stages, not simulated features.

## Open and build

Requirements: Android Studio supporting AGP 8.9.2/Kotlin 2.1.20, JDK 17, Android SDK 35 and Build Tools 35.0.0. Minimum Android 8.0 (API 26).

Open **`android/`** in Android Studio, let Gradle sync, then run `app` on a device/emulator.

```bash
cd android
./gradlew :core:test :app:lintDebug :app:lintRelease :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app starts with its offline station map and can query CT Trains without a backend or key. Debug APKs are development builds.

## Google Maps setup

Enable Maps SDK for Android in a Google Cloud project with the required billing configuration. Create an Android-restricted Maps key for `za.co.cttrains.app` and the signing certificate SHA-1; restrict its API access to Maps SDK for Android. Follow [Google's setup documentation](https://developers.google.com/maps/documentation/android-sdk/get-api-key). Google SDK rendering is used directly; Google map data is not scraped or cached into the station database.

Add this to the ignored `android/local.properties` for local builds:

```properties
CTTRAINS_MAPS_API_KEY=your-restricted-key
```

For releases, add **`CTTRAINS_MAPS_API_KEY`** as a repository Actions secret. PR builds never receive it. Use a stable production signing certificate for map-enabled releases: the fallback development certificate can change between runner builds, which affects Android key restrictions as well as APK updates. Enabling a key does not bypass user opt-in; the offline station map remains available.

## Normalized backend and source selection

The Node server now provides:

```text
GET /mobile/v1/catalog
GET /mobile/v1/journeys?from=Unibell&to=Salt%20River&date=2026-10-01&time=07:00
```

Deploy the existing server behind HTTPS. Put its base URL in `android/local.properties`:

```properties
CTTRAINS_TRANSIT_BASE_URL=https://your-transit-server.example
```

For workflow builds, use the repository Actions **variable** `CTTRAINS_TRANSIT_BASE_URL`. Do not put credentials, query strings or fragments in the URL. New installs with a configured server prefer the API. Existing saved source selection is preserved; the direct source remains selectable. Both current sources use CT Trains and are labelled accordingly. The backend and Android parser preserve separate train legs and reject unfamiliar source markup.

The [contract and provider boundaries](docs/TRANSIT_ARCHITECTURE.md#normalized-server-contract) describe how to add future providers. Existing REST endpoints and MCP tools are preserved. No backend has been deployed automatically.

## Verification

```bash
# Repository root: captured timetable/catalogue and API validation tests
npm ci --ignore-scripts
npm run test:mobile

# Android: parser/calendar/cache/source-contract/planning tests and both variants
cd android
./gradlew clean :core:test :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

The release workflow performs these checks with explicit development signing when a private key is not configured, verifies the APK signature/non-debuggable flag, and uploads APK/checksum and test/lint/schema reports. An unsigned local release can be signed through Android Studio or the documented environment variables below. Room's version-1 schema is exported under `app/schemas`; future database changes need explicit migrations.

The bundled geographic dataset and refresh/import instructions are in [STATION_DATA.md](docs/STATION_DATA.md). Parser tests use source HTML captured on 1 October 2026. Build verification does not replace device testing of notification timing, Play services, Google Maps key restrictions or calendar applications.

## GitHub Releases

After this PR is merged, changes to `android/` on `main` automatically create a GitHub prerelease with the installable APK and checksum in the repository's **Releases** section. Each automatic build gets its own `android-v1.1.0-build.N` tag, pointing at the exact source commit.

For a named version, push a tag or use **Actions → Android build and release → Run workflow** on `main`, and enter a tag such as `android-v1.1.1`:

```bash
git checkout main
git pull
git tag android-v1.1.1
git push origin android-v1.1.1
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

Use the same private key for every production version. Keep it backed up privately and outside Git. Partial signing configuration fails explicitly. The decoded key is temporary, never included in artifacts, and removed after the build. A stable tag such as `android-v1.1.1` with all four secrets configured publishes a normal release; development signatures and tags with a suffix remain prereleases. The workflow does not publish to the Play Store.


## Structure

```text
app/ui/          Map, planner, saved stations/routes, journeys, settings
app/data/        Normalized transit access, Room, Keystore and migration
app/location/    Bounded foreground location sampling
app/scheduling/  WorkManager reminders and calendar/directions handoff
core/            Pure Kotlin parsing, models, validation and planning rules
../src/transit/  Server normalization and captured-data tests
```

Independent community app; not affiliated with CT Trains, PRASA or Metrorail. Code uses the repository licence; Wikidata station coordinates are CC0.
