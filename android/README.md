# CT Trains — Smart Transit MVP

Native **Kotlin, Jetpack Compose and Google Material 3** transit planner. The app incorporates the first stage of the [smart transit proposal](docs/SMART_TRANSIT_VISION.md), with the [architecture and remaining stages](docs/TRANSIT_ARCHITECTURE.md) documented explicitly.

## Optional beta features in 1.4.0

Independent Golden Arrow and MyCiTi switches now add operator routes/stops, published day timetables, product fares and personal corrections. Map filters, street/light/dark/2025 aerial styles, nearby saved-route suggestions and local pins/photos are available. Tools includes portable safety-trip files/import, structured offline reports, and optional hosted Matrix community/chat and time-limited private trip sharing. Settings supports a local name and password-encrypted document backups. These features preserve source/freshness limits and require explicit remote/location opt-in. See [BETA_TRANSIT.md](docs/BETA_TRANSIT.md) for data provenance, privacy, limitations and device verification still needed.

Optional Google profile sign-in is implemented but awaits a Web application client ID; the supplied Android ID cannot replace it. See [GOOGLE_SIGN_IN.md](docs/GOOGLE_SIGN_IN.md). CI now publishes exact APK certificate fingerprints with its artifacts/releases.

## What works now

- Map-first home with real positions for all 102 bundled stations, tap/search selection, origin/destination controls and favourite stations.
- Online street maps with MapLibre Native and OpenFreeMap; no Google Maps SDK, Google API key, billing account or login. Online, Offline and Stations modes are selectable on Map and in Settings.
- Download the rail overview or the selected journey area for offline streets. Downloads show progress, pause/resume, deletion and storage use. The built-in station atlas works without downloading maps.
- Expand the mini map to a full-screen view with a close/back control. Selected journeys fit the view; the station atlas supports clearer labels and up to 16× zoom.
- Route traces follow bundled OpenStreetMap rail geometry between ordered timetable stops where connected. Solid lines show mapped rail traces; unresolved known-stop links remain dashed. This is approximate visualization, not an official operational path or platform walking route. See [RAIL_DATA.md](docs/RAIL_DATA.md) for provenance and the separate ODbL database licence.
- Numbered transfer pins show the change station, arriving/next trains, departure and connection time. Tapping a pin or See trains & platforms opens details. Source-provided stop platforms are preserved. Fresh explicit boarding notices can provide a labelled departure-platform report for the matching train/station/date; unknown platforms stay unknown. Train changes remain explicit even on the same line/platform.
- Expandable stops, departure/arrival times and source fares with source/fetch date.
- Direct CT Trains access and optional normalized backend source, with source switching and separate query caches.
- Native Updates & delays from `https://cttrains.co.za/status2.php`, accessible from the alerts toolbar button or Plan/tools. Refresh, line/advisory filters, text search and an optional selected-journey filter show the original messages and source report date/time. A saved snapshot can remain available for up to 48 hours, clearly labelled when refresh fails or the report date differs. HTML advertising/scripts are never executed.
- Fare calculator and Early train reporter remain accessible from Plan/tools. The fare calculator is native; the early reporter opens the original website and submission remains a user action there.
- Native trip budget calculator: check source fares for the Plan query, select single/return/weekly/monthly tickets, multiply ticket/pass quantities and travellers, and compare with an optional budget. Missing fares remain unknown. Users can enter or correct a price and remove that local override to restore the source fare.
- Saved journeys include an optional building/office/campus name, user-entered minutes to the departure station and a boarding buffer. Leave time = departure minus access time minus buffer. Optional reminders run at leave time or a custom number of minutes beforehand.
- Up to three dismissal-triggered backup reminders, each with a custom delay of 1–60 minutes and Gentle, Standard or Urgent severity. Swipe away or choose Remind again to advance; I'm leaving stops the chain. Backups stop at departure.
- Room persistence, random local UUID, Android Keystore encryption for private payloads and migration of existing DataStore preferences/routes/cache. Existing saved departure-based reminders retain their meaning.
- Calendar export through a user-reviewed Android insert intent/chooser. No calendar account or read permission is required by this app.
- Optional, cancellable foreground location sample through Android's system provider. Outside user-started beta live-trip, walking-learning or visible nearby-presence sessions there is no continuous GPS polling; no background-location permission is requested. The sample is forgotten when the app backgrounds or after five minutes.
- Favourite routes, sharing, dark/light/system themes, optional Material You colours and local personal-data deletion.

## Privacy and limits

No mandatory account, email, analytics or hardware/device identifier. Optional hosted accounts and live trip updates are described in the beta document. Profile/preferences (including price corrections), favourites, cached searches, alert snapshots and saved journey content are AES-GCM encrypted in Room. Large records, including bus geometry, use encrypted private files with small encrypted references in Room, avoiding Android's cursor row-size limit. Public station positions and indexed scheduling timestamps remain readable inside the private database. Automatic Android device backup/transfer is disabled; user-chosen encrypted document backups are available. Reinstall or resetting the profile creates a new UUID.

Search queries and IP addresses go to the selected transit source. Online maps send tile/style/font requests to OpenFreeMap; those requests reveal viewed areas and the device's IP to the provider/CDN. Station overlays and the sampled location are rendered locally. Offline and Stations modes block map HTTP requests; timetable searches and browser links are separate online operations. Location uses the device's system location implementation. Calendar export hands event information to the calendar app the user chooses. No location, health or calendar profile is uploaded to the transit backend. Explicit beta live-trip updates go separately to the chosen private Matrix room; public reports go to the chosen service room.

Downloaded maps, their geographic bounds and the temporary map cache are public, unencrypted cache data inside app-private storage. Delete downloaded areas and clear temporary tiles from Map's download panel; resetting personal data does not remove map packs. OpenFreeMap has no service-level guarantee. See its [privacy policy](https://openfreemap.org/privacy/) and [terms](https://openfreemap.org/tos/).

Timetables, mapped railways and station positions do not prove service availability. Alerts are published messages rather than train tracking; missing notices do not mean normal service. Platform notices are matched only to an explicit boarding train/station, the travel date, a report within two hours before boarding and a snapshot fetched within ten minutes. Other trains mentioned in the same message do not inherit its platform. Walking routes can be calculated with explicit consent using the public FOSSGIS foot router, cached offline, and refined using optional observed walking medians; traffic durations remain user estimates. WorkManager reminders may be delayed by Android battery controls and are not exact alarms; notification severity follows the user's Android channel, sound and Do Not Disturb settings. A missed leave time is displayed honestly, without a catch-train guarantee. Saved plans are not observed journeys and are retained for up to 90 days. Calendar events are managed separately.

Budget totals use integer cents. Corrections apply only to the exact route direction and ticket type on this device; they do not update CT Trains. Check fare validity and current prices at the ticket office. Quantity counts tickets/passes, not an inferred calendar period. The calculator excludes other transport costs unless included in the manually entered price. Optional Health Connect steps annotate completed learned walks locally. Geofencing, calendar sync and an LLM remain later stages.

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

The release workflow performs these checks with explicit development signing when a private key is not configured, verifies the APK signature/non-debuggable flag, and uploads APK/checksum/signing-certificate and test/lint/schema reports. A separate API 35 emulator job runs native large-record storage, actual Golden Arrow PDF extraction, encrypted photo backup/restore, independent/rapid settings updates, map collapse/full-screen navigation and chat/places discoverability tests. Both jobs must pass before publishing. To run device checks locally with an emulator or phone connected, use `./gradlew connectedDebugAndroidTest`. An unsigned local release can be signed through Android Studio or the documented environment variables below. Room's version-1 schema is exported under `app/schemas`; future database changes need explicit migrations.

The bundled geographic dataset and refresh/import instructions are in [STATION_DATA.md](docs/STATION_DATA.md). Parser tests use source HTML captured on 1 October 2026. Build verification does not replace device testing of notification timing, native street rendering, full-screen/transfer interactions, airplane-mode map coverage, foreground location, dismissal/backup notification actions or calendar applications.

## GitHub Releases

After this PR is merged, changes to `android/` on `main` automatically create a GitHub prerelease with the installable APK, checksum and public signing-certificate fingerprints in the repository's **Releases** section. Each automatic build gets its own `android-v1.4.0-build.N` tag, pointing at the exact source commit.

For a named version, push a tag or use **Actions → Android build and release → Run workflow** on `main`, and enter a tag such as `android-v1.4.1`:

```bash
git checkout main
git pull
git tag android-v1.4.1
git push origin android-v1.4.1
```

`android/version.properties` stores the default app version. Named tags set the APK's version name; the workflow adds its run number to the base version code. The About screen displays the built version. Failed builds or device checks never publish a release. Publishing stages a draft until all three assets are uploaded, and a rerun leaves an already published release unchanged.

### Signing

With no signing secrets configured, the workflow explicitly opts into **development signing** using an Android debug key. These release-variant APKs are installable and not debuggable, but are marked **prereleases** and labelled `development`. The key may change between workflow runs. If Android rejects an update due to a signature mismatch, uninstall the previous development build before installing the new one; this also removes its locally saved data.

For stable production signing, add all four repository **Actions secrets** under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `CTTRAINS_KEYSTORE_BASE64` | Single-line Base64 of your private release keystore |
| `CTTRAINS_KEYSTORE_PASSWORD` | Keystore password |
| `CTTRAINS_KEY_ALIAS` | Signing key alias |
| `CTTRAINS_KEY_PASSWORD` | Signing key password |

Use the same private key for every production version. Keep it backed up privately and outside Git. Partial signing configuration fails explicitly. The decoded key is temporary, never included in artifacts, and removed after the build. A stable tag such as `android-v1.3.1` with all four secrets configured publishes a normal release; development signatures and tags with a suffix remain prereleases. The workflow does not publish to the Play Store.


## Structure

```text
app/ui/          Map/downloads/transfers, alerts, planner, budget, journeys, settings
app/data/        Normalized transit access, Room, Keystore and migration
app/maps/        Native rendering and bounded offline downloads
app/location/    Bounded system-provider location sampling
app/scheduling/  WorkManager reminders and calendar/map handoff
core/            Pure Kotlin parsing, models, validation and planning rules
../src/transit/  Server normalization and captured-data tests
```

Independent community app; not affiliated with CT Trains, PRASA or Metrorail. Code uses the repository licence; Wikidata station coordinates are CC0. Street-map attribution/provider terms and the bundled ODbL railway database remain separate from the code licence.

See [the 1.5 quality-of-life notes](docs/QUALITY_OF_LIFE_1_5.md) for map fixes, Golden Arrow source pagination, saved places, walking-route consent and the optional learning/nearby features.

See [the 1.6 final touch-up notes](docs/FINAL_TOUCHUPS_1_6.md) for journey focus, segment labels, address suggestions, operator planning and route steps.


## Active directions and GitHub updates (1.9)

Choose a timed journey in Plan, then tap **Directions** for walking guidance, boarding, changes, and the final walk. Explicitly started directions use a location foreground service with pause/stop controls, Android 16 Live Updates and an ordinary ongoing notification on older Android. Samsung controls eligibility and Now Bar placement. Boarding/getting off require confirmation; scheduled times are not live vehicle arrivals. GPS stays in memory, with no directions location history.

**Tools / Settings → App updates · GitHub** checks published release manifests and stages the next APK while the installed app remains available. Checksums, package/version, Android requirements and cryptographic APK signatures must pass before Android's installation confirmation. This is app-level staging, not system A/B slots or automatic rollback. Compatible upgrades require a persistent signing key; private repositories require a device-configured Contents: read-only token. See the bundled [directions and updates guide](docs/DIRECTIONS_AND_UPDATES.md) for phone settings and publisher setup.

## Journey planner and comparison (1.8)

Plan has a single **Mode of transport** selector for Trains, Golden Arrow, MyCiTi or Compare. Switching keeps the same From/To saved places or stops. Walking requires explicit routing consent. Published direct MyCiTi trips use exact stop IDs and ordered timed calls. The comparison starts at the same leave-after time, includes access/final walks, wait and a five-minute boarding buffer, and excludes departures the traveller cannot catch. Missing walks leave door-to-door duration unknown. Fastest found describes only complete options among the checked routes, not a citywide optimum. At most six candidate bus routes and three boarding/alighting pairs per route are checked; bus transfers and intermodal routing are not implemented.

Calendar/time selectors and complete From/To swapping are available in Plan. **Auto estimate** searches nearby usable stops and mapped walks; leave reminders can calculate access time and preview the leave-by clock. See [1.8 changes](docs/QUALITY_OF_LIFE_1_8.md).

Golden Arrow survey route codes are not an authoritative join to published PDF timed points. **Manual bus setup** lets the user personally bind boarding and alighting timed points to surveyed map pins. Later searches reuse those explicit bindings, with an unverified-current-operation label. Without bindings, matching surveyed corridors surface published timetable candidates and clearly explain missing roadside times. The catalogue includes the publisher's advertised letter pages; continuation PDF pages and late weekday notes are parsed. The app never invents schedules or cash fares from weekly/monthly passes. Choose the actual bus fare band/payment product, or enter a personal correction in Bus schedules → Fares. The bus browser uses one scrolling list, with collapsed filters/source details; map time badges hide at low zoom and avoid overlap.

## WhatsApp updates (optional alpha)

Open **Tools → WhatsApp updates · alpha** or **Settings → Beta features → WhatsApp updates · alpha**. Trip/report sharing remains in Tools → Safety trips and Community reports and uses Android's share sheet for review.

The optional reader implements the API in [rmyndharis/OpenWA](https://github.com/rmyndharis/OpenWA), checked against commit `022f4bc97fca9139b0b14bf3eeae1fc0fac3a91a`. OpenWA must run on a separate gateway with a linked WhatsApp account; it is not embedded in this Android app. Open **How to set this up** for the bundled offline guide, or read [WhatsApp setup](docs/WHATSAPP_SETUP.md). Enter the HTTPS gateway website and a restricted viewer/Reader key, tap **Find linked accounts**, select the ready account and **Test & connect reader**. The app adds `/api` to an origin-only website, supports an explicit custom path and offers manual session UUID entry. Setup endpoints were rechecked against upstream documentation and the session controller on 2 October 2026. Channel browsing/reading requires the **whatsapp-web.js** engine (the Baileys channel endpoints return 501). Follow the actual PRASA channel using that account and select its returned identity; no official channel ID is assumed. Group reading is restricted to explicitly selected group IDs.

The reader only issues GETs for account discovery/connection checks and selected feeds. It caches a bounded, dated, unverified inbox encrypted locally; it does not rewrite official schedules, manage accounts, join groups or automatically send/schedule outgoing messages. Review & share opens Android's share sheet. Foreground checks occur about once a minute while the reader screen is open; optional WorkManager checks run at a minimum fifteen-minute interval subject to Android scheduling. Failures keep dated cached posts and do not fail transit refresh. Pause cancels background checks; disconnect erases credentials/inbox. Credentials and WhatsApp posts are excluded from portable backups and cleared during profile replacement. OpenWA is unofficial and may lead to account restrictions; use the dedicated account you choose, not another person's account.

Parser/contract checks and setup UI are tested. Live channel/account integration requires a real gateway and account and has not been exercised by CI.
