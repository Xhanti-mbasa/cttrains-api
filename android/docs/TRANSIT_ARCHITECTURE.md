# Smart transit implementation and next stages

The original product brief is preserved in [SMART_TRANSIT_VISION.md](SMART_TRANSIT_VISION.md). This release implements its transit MVP and establishes boundaries for the later context and intelligence stages. Version 1.4 adds independent beta bus adapters, map layers, portable profiles/trips and opt-in hosted reports/chat/private check-ins; their current boundaries and source provenance are documented in [BETA_TRANSIT.md](BETA_TRANSIT.md). Multi-operator journey planning and verified live operator feeds remain future work.

## Implemented MVP

| Capability | Implementation |
| --- | --- |
| Map-first home | 102 real station positions, station taps/search, pan/zoom, origin/destination selection |
| Street maps | MapLibre Native/OpenFreeMap without a key or login; online mode, bounded SDK offline regions with progress/pause/delete, and station-atlas fallback |
| Route visualization | Bundled OSM rail graph follows mapped connections between ordered stops; dashed fallback for unresolved known-stop pairs, no bridge across unknown positions; full-screen view |
| Transfers/platforms | Numbered change pins and accessible details with train IDs/times; optional per-stop source platform and narrowly matched fresh boarding notice; unknown remains unknown |
| Alerts | Native status2.php messages, stable IDs, source date/time, line/search/journey filters, refresh and labelled 48-hour saved snapshot |
| Timetables and fares | Direct CT Trains source and optional normalized server source; source/fetch date shown; exact-query/source offline cache |
| Source switching | Available source chips. Both current adapters use CT Trains; source/query caches stay separate |
| Favourites | Local favourite stations and routes, existing favourite routes migrated |
| Profile and storage | Random local UUID, Room, Keystore encryption for personal record content, old DataStore migration |
| Journey planning | Building label, user-entered access/buffer estimate, deterministic leave-by time and custom lead before leaving |
| Notifications | Permission-gated WorkManager reminders; up to three dismissal-triggered backups with individual delay/severity; acknowledgement stops the chain, expired departures are rejected |
| Budget | Explicit source fare parsing, exact integer-cent totals, ticket/pass quantities and travellers; encrypted route/ticket-specific local corrections with source restoration |
| Website tools | Native updates/delays and fare/budget calculator; early reporter browser handoff, no automatic submissions |
| Calendar | User-reviewed insert intent and app chooser, works with an installed calendar app; no background sync/read permission |
| Location | Optional coarse/fine permission, one bounded foreground sample via Android system providers; forgotten when app backgrounds |
| Privacy | No mandatory account, hardware identifiers, analytics, cloud behavioral profile or health permission. Explicit, time-limited live trip sharing uses a foreground service; no background-location permission. Reset deletes local personal data and cancels app reminders |

Map display does not prove service availability. Timetable snapshots are not live train tracking. WorkManager delivery is approximate and may be delayed by Android battery controls. A saved plan is not recorded as an observed journey. Calendar events remain under the user's calendar account and are not deleted when local app data is reset.

## Boundaries

`ui/` uses Navigation Compose and observes `TrainViewModel` and `FeatureViewModel`. They coordinate `data/`, `maps/`, `location/` and `scheduling/`. The `core` module contains normalized journeys, source/query validation, time arithmetic, cache policy and planning rules without Android dependencies. `data/LocalTransitStore` owns Room and the one-time migration; `SecureCodec` protects private payloads with an AES-GCM Android Keystore key. Large payloads use immutable encrypted private files and small encrypted Room references. Public station positions and indexed scheduling timestamps are not encrypted. The separate MapLibre database stores unencrypted public map resources and region bounds; personal-data reset preserves map downloads, which have their own deletion controls. Automatic Android backup/device transfer remain disabled. User-chosen password-encrypted backups can restore supported local data after reinstall; document permission and password must be supplied again.

`TrainRepository` presents normalized source results. The configured API adapter allows the scraper to run outside the Android app. The existing parser remains a direct-source fallback so the app still works without a deployment. Caches are keyed by source and exact query, with 12 entries/14-day limits. Personal preference updates are serialized. Reminder input contains random journey/profile identifiers, generation tokens and stage numbers; the worker loads encrypted content locally. No personal profile, calendar content, health information or location is sent to the transit server.

## Map and reminder boundaries

`maps/MapEngine` controls a map-only HTTP client. Switching to Offline/Stations cancels in-flight map requests and disables the SDK network gate before the preference is persisted. The street view waits for loaded preferences, preserving offline mode across launches. Timetable/browser requests are independent. Online provider requests reveal viewed regions and IP; locally rendered station/route/location overlays are not posted to the map provider. Attribution stays visible. Offline definitions include style/sprites/fonts, cap zoom/area estimates, pause on backgrounding, and require explicit resume. The 200 MB budget is a soft download limit, not a guarantee of exact disk use.

New plans schedule against leave-by time; legacy payloads default to departure-based reminders. `JourneyPlanning.afterDismiss` validates generation/stage and anchors each backup delay to the dismissal time. Atomic encrypted record updates prevent duplicate dismissal actions from advancing twice. Acknowledgement marks the chain stopped and cancels work/notifications. Backup work has distinct stage names and a per-journey tag; startup restoration checks history/generation before recovering missing work. Worker checks prevent stale profiles, generations, stages, stopped plans and past departures from posting. WorkManager and Android notification channels govern actual delivery; urgent does not bypass Do Not Disturb.

`TripBudget` recognizes explicitly labelled rand amounts and never infers absent prices. Corrections use `(from, to, ticketId)` keys and timestamps; direction and pass type remain distinct. Quantity and traveller bounds keep exact-cent arithmetic finite. Native budget refresh uses the same validated query/source path as Plan; saved snapshots and their fetch time remain labelled. Corrections affect only local calculations.

## Alerts, rail traces and platforms

`ServiceAlerts` reads the captured status2 message-row structure and report date, rejects unfamiliar/partial markup and ignores ads/scripts. IDs hash the report date, group, time and message so repeated fetches do not duplicate notices. Room's encrypted local-value table stores a single bounded snapshot, usable for up to 48 hours. Refresh failure is shown separately from published message times; an empty published message list is not confirmation of normal service. The alerts source is CT Trains directly, independent of the selected timetable adapter.

`RailRouter` loads the bundled ODbL graph off the UI thread and computes each consecutive timetable stop pair against shared OSM node IDs. A bounded search from nearby station rail candidates preserves mapped curves and avoids unnecessary loops from a single parallel-track snap; it does not connect grade-separated rails just because their coordinates are near. Missing anchors, disconnected graphs or implausible detours produce an explicit dashed approximation only when both station positions are known. Current journey traces are computed with cancellable state collection; stale results cannot replace another selected journey. Offline-download bounds include the known trace. This is approximate visualization rather than authoritative operational train routing. See [RAIL_DATA.md](RAIL_DATA.md).

`Stop.platform` defaults to null, preserving old cached/scheduled payloads. Direct and normalized parsers accept explicit per-stop platform attributes/classes, never a platform found elsewhere in a journey card. Transfer markers always identify the arriving and next train, even when line colours or known platform numbers are unchanged. A published boarding notice can supply a labelled platform only for the exact train, station and travel date, with a ten-minute snapshot freshness limit and a report no more than two hours before boarding. Connecting trains mentioned elsewhere in the message do not inherit that platform. Missing arrival/departure assignments remain unknown; no platform walking route is fabricated.

## Normalized server contract

The Node server adds `/mobile/v1/catalog` and `/mobile/v1/journeys` without changing legacy endpoints or MCP tools. Host it over HTTPS and set `CTTRAINS_TRANSIT_BASE_URL` to its base URL.

```text
GET /mobile/v1/catalog
→ { stations: ["Unibell", ...], holidays: ["YYYY-MM-DD", ...] }

GET /mobile/v1/journeys?from=Unibell&to=Salt%20River&date=2026-10-01&time=07:00
→ {
  query: { from, to, date, time },
  journeys: [{ legs: [{ line, train, stops: [{ name, time }] }] }],
  fetchedAt: <epoch milliseconds>,
  fares: <source text or null>,
  sourceId: "cttrains-api"
}
```

The backend validates station names, real calendar dates, source service restrictions and time intervals. It retains train legs/transfers and rejects unexpected markup rather than returning false empty results. The client verifies query, source, endpoints, stop times, connections and timestamp before storing the response. The existing one-hour server cache retains the original fetch time. A future provider should have a distinct source ID and adapter, with its own capabilities, terms and fresh/stale status. Do not repurpose a source ID or join incompatible schedules.

## Stage 2: context and disruption integration

Add a licensed walking/transit-route provider, verified disruption-to-journey impact, event-driven geofences and explicit activity consent. Published CT Trains status messages are already shown natively, but do not silently rewrite a timetable or imply a live train position. Collect opt-in observed journeys, immediately aggregate access/transfer durations and expire raw events. Implement median/95th-percentile travel estimates only after sufficient observations. Show uncertainty and distinguish user-entered estimates from measured or provider-supplied estimates. Request background location only for a specific opt-in feature that needs it. Refresh an imminent journey and present alternatives; do not silently replace the user's plan. Integrate weather through a separate provider and avoid guessed rain adjustments.

## Stage 3: routines and calendar/health context

Add revocable, granular Health Connect and calendar-read consent, local aggregation and deletion/retention controls. Store planned wake time separately from observed movement/wake estimates. Do not infer actual travel from a saved journey or actual waking from an alarm. Calendar sync must track only app-created events and require confirmation for changes. Health observations remain local. Forecasts require real observations, a documented minimum sample count and calibration; do not display invented catch-train probabilities.

## Stage 4: optional assistant and MCP

The existing MCP transit server can serve schedule tools. Keep device context private behind user-mediated, scoped tool access; do not expose Room or location on an unauthenticated network port. Give a future model structured results from deterministic tools for time, routing and reminders. Validate every proposed action against current data and permission state. Require user confirmation for calendar changes, notifications to contacts and other sensitive actions. Keep the deterministic planner functional without a model.

Benchmark optional Qwen/Gemma/Phi/Llama models on target Android devices before choosing a runtime: model licence, download size, RAM, time to first token, sustained speed, battery and thermal impact, offline behavior and opt-out/deletion. Compare llama.cpp, MediaPipe or ONNX Runtime with the same small retrieval-based task. No large model is bundled or continuously running in this release. Health/sleep context, learned probabilities, live traffic, verified delay predictions and agentic actions are not implemented or simulated by the MVP.
