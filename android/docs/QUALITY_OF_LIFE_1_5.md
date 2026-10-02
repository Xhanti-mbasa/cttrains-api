# CT Trains 1.5 quality-of-life update

Map options (connectivity, independent service layers, downloads and imagery) now live in a collapsible bottom sheet. The full-screen map hides the navigation bars and journey cards. Home can scroll and refresh by pulling down; both map sizes have an explicit Refresh action. Open in Google uses Google's Maps URLs and hands off to the installed app or browser, with no API key. The visible map centre is used when no complete trip is selected. Tools is labelled in the top bar.

Navigation crossfades are disabled so two native map surfaces do not overlap during transitions. Bus layers, rail overlays and GeoJSON serialization run off the main thread; unchanged sources are not resent to MapLibre. Offline survey paths are prepared once and transformed while panning. Camera position is retained when switching between online imagery styles. Settings transformations are executed inside the existing atomic store update: quick independent toggles cannot overwrite each other. Train View map clears an old bus route filter and enables the train layer. Returning through the Map navigation tab clears the selected bus-route filter.

## Golden Arrow source correction

The provincial stop layer contains 104,734 route-membership records. Its grouped-statistics queries ignore resultOffset, even though ordinary feature queries support paging. The client groups valid stop codes, requests the minimum OBJECTID per code, and advances using BUSSTOPNO > the last returned code. It requests WGS84 geometries in batches of 400. The 533 unnumbered memberships are fetched separately and deduplicated by code/position. There are 3,569 unique representative survey points in the verified bundled snapshot, including 3,546 numbered stops. Representative records are not verified separate boarding platforms or directions; their route codes are never assumed to match timetable documents.

Source: https://gis.westerncape.gov.za/server2/rest/services/SpatialDataWarehouse/Transportation/MapServer/6

The 1,991 survey route records are generalized by the source to 0.00015 degrees for display (144,979 vertices in this check). Map lines remain approximate. A first install can display the bundled survey points while the live catalogue/fares/map check runs. The seed contains no invented fares or timetable routes. Failures retain source dates and are visible. The operator's site may reject automated requests; open the published source rather than invent a schedule.

## Saved places and walking

Mark places by long-pressing the map or using Map options → Mark map centre, then choose Home, Work, Campus, Friend or Custom. Existing pins/photos are retained. Plan → Door-to-door walking offers a start location and destination after the station, including a refreshed current location. A closest mapped railway station is suggested, and can be changed manually.

An explicit consent dialog precedes any request to the public FOSSGIS walking router. Both selected coordinates go to that service and can enter its server logs. Requests use its foot dataset, are serialized to at most one per 1.1 seconds, and respect bounded response size, path snapping and coordinate validation. Geometry, distance and duration come from OSRM, not a straight-line speed assumption. Up to 20 encrypted walking results are saved locally and included in user-selected encrypted backups. Fresh saved results are reused; failed online requests can use dated cached paths. Without a mapped/cached walking leg, only straight-line distance is shown and walking time remains unknown.

Door-to-door times combine the selected (or first listed) train, walking legs and a five-minute boarding buffer, including arrival after midnight. These are estimates, not live arrival promises. Solid rail and dashed walking paths are displayed on the same map. MyCiTi alternatives require exact published route membership near both endpoints and still require timetable direction/time verification. Golden Arrow survey data cannot verify an intermodal timetable. The train alternative searches the existing published train source; this release does not invent train–bus transfer schedules.

Router policy/privacy: https://routing.openstreetmap.de/about.html
OSRM API: https://project-osrm.org/docs/v5.24.0/api/#route-service
Google Maps URL handoff: https://developers.google.com/maps/documentation/urls/get-started

## Optional beta observation and nearby presence

Walking learning is off by default. A user starts a specific walking segment while the app is visible. A foreground location service with a persistent Stop notification continues for at most two hours; it never restarts on boot. The start must be within 100 m, fixes within 60 m accuracy and 30 seconds old, and arrival requires two fixes at least 15 seconds apart. Recorded speed must be consistent with walking. Only aggregate elapsed duration, distance and segment identity are retained, up to 20 per segment / 200 total, without a GPS trail. At least three comparable observations are required before using a median. Android/provider restrictions can prevent learning a walk.

Nearby users is separate and off by default. It uses consenting Matrix accounts in a selected service room, not global user discovery or verified identities. After a separate consent/start action, positions are rounded to 0.01 degrees (about 1 km), shared approximately once a minute, expire for app display after five minutes, and are filtered to within 5 km. The app sends no exact GPS position for this feature. Matrix room members/providers can retain these coarse messages in history. Leaving the screen, backgrounding the app, stopping, disabling the feature or disconnecting revokes local sharing; an online stop message is attempted, otherwise prior presence expires. A visible session lasts at most 15 minutes. No nearby GPS history is written to a portable profile.

Chat displays separate loading/off/account/room states and account setup inside Chat. Sync has its own job instead of blocking the common action flag, messages refresh after sending, and drafts clear only on successful sends. Switching service does not display another room's messages. WhatsApp report text and report-file sharing retain their existing format.

## Verification limits

Core tests cover real OSRM response parsing, failure/snap rejection, midnight door-to-door times, learned median/outliers, bus connection checks, nearby precision/expiry/revocation, grouped-query pagination and the complete bundled survey. Android tests cover map collapse/full screen, rapid settings updates/train-layer recovery, saved-place discoverability and chat setup, alongside existing native storage/PDF/backup tests. Actual Google OAuth consent, two-account Matrix exchanges and background walking on a physical phone require those environments and are not implied by emulator or unit tests.
