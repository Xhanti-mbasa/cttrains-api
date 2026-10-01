# CT Trains — Smart Transit MVP

Native **Kotlin, Jetpack Compose and Google Material 3** transit planner. The app incorporates the first stage of the [smart transit proposal](docs/SMART_TRANSIT_VISION.md), with the [architecture and remaining stages](docs/TRANSIT_ARCHITECTURE.md) documented explicitly.

## What works now

- Map-first home with real positions for all 102 bundled stations, tap/search selection, origin/destination controls and favourite stations.
- Online street maps with MapLibre Native and OpenFreeMap; no Google Maps SDK, Google API key, billing account or login. Online, Offline and Stations modes are selectable on Map and in Settings.
- Download the rail overview or the selected journey area for offline streets. Downloads show progress, pause/resume, deletion and storage use. The built-in station atlas works without downloading maps.
- Separate train-leg connections, transfers, expandable stops, departure/arrival times and source-provided fares with source/fetch date. Connections are not railway track geometry or walking directions.
- Direct CT Trains access and optional normalized backend source, with source switching and separate query caches.
- The website's Updates & delays, Fare calculator and Early train reporter controls are available from Plan and the toolbar's tools menu. Updates/reports open the original website; submitting a report remains a user action there.
- Native trip budget calculator: check source fares for the Plan query, select single/return/weekly/monthly tickets, multiply ticket/pass quantities and travellers, and compare with an optional budget. Missing fares remain unknown. Users can enter or correct a price and remove that local override to restore the source fare.
- Saved journeys include an optional building/office/campus name, user-entered minutes to the departure station and a boarding buffer. Leave time = departure minus access time minus buffer. Optional reminders run at leave time or a custom number of minutes beforehand.
- Up to three dismissal-triggered backup reminders, each with a custom delay of 1–60 minutes and Gentle, Standard or Urgent severity. Swipe away or choose Remind again to advance; I'm leaving stops the chain. Backups stop at departure.
- Room persistence, random local UUID, Android Keystore encryption for private payloads and migration of existing DataStore preferences/routes/cache. Existing saved departure-based reminders retain their meaning.
- Calendar export through a user-reviewed Android insert intent/chooser. No calendar account or read permission is required by this app.
- Optional, cancellable foreground location sample through Android's system provider. No continuous GPS polling, raw GPS history or background location permission. The sample is forgotten when the app backgrounds or after five minutes.
- Favourite routes, sharing, dark/light/system themes, optional Material You colours and local personal-data deletion.

## Privacy and limits

No mandatory account, email, analytics, hardware/device identifier or cloud behavioral profile. Profile/preferences (including price corrections), favourites, cached searches and saved journey content are AES-GCM encrypted in Room. Public station positions and indexed scheduling timestamps remain readable inside the private database. Device backup/transfer is disabled. Reinstall or resetting the profile creates a new UUID.

Search queries and IP addresses go to the selected transit source. Online maps send tile/style/font requests to OpenFreeMap; those requests reveal viewed areas and the device's IP to the provider/CDN. Station overlays and the sampled location are rendered locally. Offline and Stations modes block map HTTP requests; timetable searches and browser links are separate online operations. Location uses the device's system location implementation. Calendar export hands event information to the calendar app the user chooses. No location, health, calendar or behavioral profile is uploaded by the app to its transit backend.

Downloaded maps, their geographic bounds and the temporary map cache are public, unencrypted cache data inside app-private storage. Delete downloaded areas and clear temporary tiles from Map's download panel; resetting personal data does not remove map packs. OpenFreeMap has no service-level guarantee. See its [privacy policy](https://openfreemap.org/privacy/) and [terms](https://openfreemap.org/tos/).

Timetables and station positions are not live service availability. Walking/traffic durations are the user's estimates. WorkManager reminders may be delayed by Android battery controls and are not exact alarms; notification severity follows the user's Android channel, sound and Do Not Disturb settings. A missed leave time is displayed honestly, without a catch-train guarantee. Saved plans are not observed journeys and are retained for up to 90 days. Calendar events are managed separately.

Budget totals use integer cents. Corrections apply only to the exact route direction and ticket type on this device; they do not update CT Trains. Check fare validity and current prices at the ticket office. Quantity counts tickets/passes, not an inferred calendar period. The calculator excludes other transport costs unless included in the manually entered price. Health Connect, geofencing, learned estimates, calendar sync and an LLM remain later stages.

## Open and build

Requirements: Android Studio supporting AGP 8.9.2/Kotlin 2.1.20, JDK 17, Android SDK 35 and Build Tools 35.0.0. Minimum Android 8.0 (API 26).

Open **`android/`** in Android Studio, let Gradle sync, then run `app` on a device/emulator.

```bash
cd android
./gradlew :core:test :app:lintDebug :app:lintRelease :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app starts in Online map mode, with a station-atlas fallback, and can query CT Trains without a backend or key. Switch to Offline or Stations to disable map network access. Debug APKs are development builds.

## Online and offline maps

MapLibre Native 13.6.1 renders the [OpenFreeMap Liberty style](https://tiles.openfreemap.org/styles/liberty), with visible OpenMapTiles/OpenStreetMap attribution. No map key or Google login is needed, including workflow builds.

1. Open Map, tap the download icon, and choose Rail overview or Selected journey.
2. Keep the app open while downloading; leaving the app pauses downloads. Resume explicitly when connected. Wi-Fi is recommended.
3. After completion, tap Use offline or choose Offline mode. The saved area's bounds and zoom range constrain the offline street view. Select another saved area from the download panel.
4. Use Stations for the bundled atlas; it covers mapped stations without a street-map download.

The app allows up to three regions and an estimated 5,000 tile coordinates per region. Overview zooms are 8–12; journey zooms are 10–14. Downloads pause around a shared 200 MB resource budget; in-flight resources may slightly exceed this soft limit. The temporary online cache has a 32 MB target. Partial areas can be resumed or deleted. Offline coverage applies only to the downloaded area and zooms; it does not download schedules or walking routes. Downloads use the SDK's offline region API and OpenFreeMap, not bulk downloads from the standard OpenStreetMap raster service.

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

The bundled geographic dataset and refresh/import instructions are in [STATION_DATA.md](docs/STATION_DATA.md). Parser tests use source HTML captured on 1 October 2026. Build verification does not replace device testing of notification timing, native street rendering, airplane-mode map coverage, foreground location, dismissal/backup notification actions or calendar applications.

## GitHub Releases

After this PR is merged, changes to `android/` on `main` automatically create a GitHub prerelease with the installable APK and checksum in the repository's **Releases** section. Each automatic build gets its own `android-v1.2.0-build.N` tag, pointing at the exact source commit.

For a named version, push a tag or use **Actions → Android build and release → Run workflow** on `main`, and enter a tag such as `android-v1.2.1`:

```bash
git checkout main
git pull
git tag android-v1.2.1
git push origin android-v1.2.1
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

Use the same private key for every production version. Keep it backed up privately and outside Git. Partial signing configuration fails explicitly. The decoded key is temporary, never included in artifacts, and removed after the build. A stable tag such as `android-v1.2.1` with all four secrets configured publishes a normal release; development signatures and tags with a suffix remain prereleases. The workflow does not publish to the Play Store.


## Structure

```text
app/ui/          Map/downloads, planner, budget, service tools, journeys, settings
app/data/        Normalized transit access, Room, Keystore and migration
app/maps/        Native rendering and bounded offline downloads
app/location/    Bounded system-provider location sampling
app/scheduling/  WorkManager reminders and calendar/map handoff
core/            Pure Kotlin parsing, models, validation and planning rules
../src/transit/  Server normalization and captured-data tests
```

Independent community app; not affiliated with CT Trains, PRASA or Metrorail. Code uses the repository licence; Wikidata station coordinates are CC0. Street-map attribution and provider terms remain separate from the code licence.
