# CT Trains 1.6

## Map and search

Map options and Settings offer **Focus on selected journey**. It hides unrelated stations, bus stops, shapes, saved pins and friends. Selected endpoints, boarding/alighting stations, train changes, explicitly selected pins and calculated walks remain visible. Layer switches and beta operator switches remain independent. Turning focus off restores the full map. The aerial/style badge opens Map options.

Native street/aerial/downloaded maps and the offline station canvas display segment labels. Walking labels use actual returned route distance and ceiling-rounded minutes, rather than straight-line distance or a guessed walking speed. Rail labels use each published leg's times. The overall duration includes train change time and available access/final walks, excluding boarding buffer and unreported delays. Focus never removes intermediate coordinates used to draw railway traces.

Address suggestions require opt-in in the search sheet. Photon supports search-as-you-type; public Nominatim does not permit autocomplete and is not used for it. Searches are restricted to Cape Town, delayed by 850 ms, cancelled when text changes, globally limited to one request every two seconds, cached in a bounded memory cache and backed off after HTTP 429. Exact house numbers appear only when the provider returns them. Coverage and availability are not guaranteed. Search can still find enabled operator stops and saved places when map focus hides them. Selected places can be marked and saved offline.

Sources: [Photon API/policy](https://github.com/komoot/photon), [FOSSGIS routing](https://routing.openstreetmap.de/about.html).

## Bus planning and fares

Plan offers Trains and each independently enabled bus operator. Bus plans select a published route, day/date, time, and timed boarding/alighting calls. Only exact operator IDs in the correct trip order match. No departure, transfer, fare or stop association is invented. Candidates near selected places are route membership suggestions; the timed trip must be checked separately. Empty direct results explain the limit and offer the Trains alternative. This version does not automatically combine bus transfers or mixed bus/rail journeys.

MyCiTi coordinates match exact stop IDs. Access/final walks are calculated with explicit routing consent and cached. Golden Arrow PDF timetable points are not automatically joined to provincial survey codes. Users can explicitly choose surveyed boarding/alighting points; those associations remain unverified, and no fabricated bus shape joins them. Bus plans without published/selected coordinates remain usable as timetables but have no walking geometry.

Fares have a bounded scroll area and collapsible source details. Published cash/single products are displayed when present. Missing Golden Arrow cash prices are unknown: the app never divides weekly/monthly prices to invent a single fare. Users can store a confirmed personal cash quote and budget by product count. On 25 September 2026 the provincial government announced changes to selected off-peak cash prices effective 28 September; old multi-journey products cannot determine these prices. [Official notice](https://www.westerncape.gov.za/mobility/article/minister-sileku-welcomes-gabs-decision-protect-commuters-targeted-fare-adjustments).

## Health and nearby permissions

Health Connect is optional, read-only, and requests **READ_STEPS** only. The app reads deduplicated aggregate steps for the exact time interval of recent walking segments the user explicitly learned, while the app is visible. No background-health, history, heart-rate, sleep or write access is requested. Missing data stays unknown; no health record is sent in trip files, nearby presence, Matrix or WhatsApp. Step totals annotate observations; duration medians, not an assumed stride length, refine walking estimates. Backups can include encrypted observations; restore pauses health opt-in. Android compile SDK 36 satisfies the stable Health Connect dependency, with minimum/target SDK behaviour preserved.

In Samsung Health, enable Health Connect permissions for writing Steps, sync the watch and launch Samsung Health. Then use Settings → Route steps → Update steps for recent completed walks. Watch sync can be delayed. Actual Samsung/watch permission and sync behaviour still requires device testing. [Samsung FAQ](https://developer.samsung.com/health/health-connect-faq.html), [Android aggregation](https://developer.android.com/health-and-fitness/health-connect/aggregate-data).

Nearby users uses consenting Matrix accounts and approximate room presence. It does not scan Bluetooth devices, so Android Nearby devices permission is not appropriate. Connect a Matrix account, join a service room, enable Nearby users, then explicitly start sharing to request location permission. Entries expire locally; server history can remain. Private friends use separate invite-only live-trip rooms: create an invitation, share the file with the intended friend, and independently start sharing/watching. Sharing stops after two hours or via the notification. Google profile login does not authenticate Matrix. Two-account live sharing and physical location tracking still require device testing.

## Validation

Regression coverage includes exact bus ID/direction matching, journey focus endpoints, returned walking distance/minutes, rejecting out-of-region geocoding and not fabricating house numbers, native operator navigation and read-only permission declarations. CI also builds debug/release APKs, runs lint and existing native persistence/map/chat regressions. Real operator availability, watch sync and connected friends are not guaranteed by build or emulator checks.
