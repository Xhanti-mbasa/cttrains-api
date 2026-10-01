# Smart transit implementation and next stages

The original product brief is preserved in [SMART_TRANSIT_VISION.md](SMART_TRANSIT_VISION.md). This release implements its transit MVP and establishes boundaries for the later context and intelligence stages.

## Implemented MVP

| Capability | Implementation |
| --- | --- |
| Map-first home | 102 real station positions, station taps/search, pan/zoom, origin/destination selection |
| Google Maps | Maps Compose SDK with build-time key and explicit opt-in; offline station map without a key |
| Route visualization | Separate coloured train-leg connections over known stops; no invented track or walking geometry |
| Timetables and fares | Direct CT Trains source and optional normalized server source; source/fetch date shown; exact-query/source offline cache |
| Source switching | Available source chips. Both current adapters use CT Trains; external Google directions are a separate comparison |
| Favourites | Local favourite stations and routes, existing favourite routes migrated |
| Profile and storage | Random local UUID, Room, Keystore encryption for personal record content, old DataStore migration |
| Journey planning | Saved journeys, bounded custom reminder lead, user-entered access/buffer estimate and deterministic leave-by time |
| Notifications | Permission-gated WorkManager reminders; cancelled when plan is removed; expired departures never notified |
| Calendar | User-reviewed insert intent and app chooser, compatible with installed Google Calendar; no background sync/read permission |
| Location | Optional coarse/fine permission, one balanced-power bounded foreground sample; forgotten when app backgrounds |
| Privacy | No account, hardware identifiers, analytics, cloud behavioral profile, health permission or background GPS. Reset deletes local personal data and cancels app reminders |

Map display does not prove service availability. Timetable snapshots are not live train tracking. WorkManager delivery is approximate and may be delayed by Android battery controls. A saved plan is not recorded as an observed journey. Calendar events remain under the user's calendar account and are not deleted when local app data is reset.

## Boundaries

`ui/` uses Navigation Compose and observes `TrainViewModel`. The ViewModel coordinates `data/`, `location/` and `scheduling/`. The `core` module contains normalized journeys, source/query validation, time arithmetic, cache policy and planning rules without Android dependencies. `data/LocalTransitStore` owns Room and the one-time migration; `SecureCodec` protects private payloads with an AES-GCM Android Keystore key. Public station positions and indexed scheduling timestamps are not encrypted. Backup and device transfer remain disabled; reinstall creates a new local UUID.

`TrainRepository` presents normalized source results. The configured API adapter allows the scraper to run outside the Android app. The existing parser remains a direct-source fallback so the app still works without a deployment. Caches are keyed by source and exact query, with 12 entries/14-day limits. Personal preference updates are serialized. Reminder input contains only random journey/profile identifiers; the worker loads encrypted content locally. No personal profile, calendar content, health information or location is sent to the transit server.

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

## Stage 2: context and disruptions

Add a licensed walking/transit-route provider, verified disruptions, event-driven geofences and explicit activity consent. Collect opt-in observed journeys, immediately aggregate access/transfer durations and expire raw events. Implement median/95th-percentile travel estimates only after sufficient observations. Show uncertainty and distinguish user-entered estimates from measured or provider-supplied estimates. Request background location only for a specific opt-in feature that needs it. Refresh an imminent journey and present alternatives; do not silently replace the user's plan. Integrate weather through a separate provider and avoid guessed rain adjustments.

## Stage 3: routines and calendar/health context

Add revocable, granular Health Connect and calendar-read consent, local aggregation and deletion/retention controls. Store planned wake time separately from observed movement/wake estimates. Do not infer actual travel from a saved journey or actual waking from an alarm. Calendar sync must track only app-created events and require confirmation for changes. Health observations remain local. Forecasts require real observations, a documented minimum sample count and calibration; do not display invented catch-train probabilities.

## Stage 4: optional assistant and MCP

The existing MCP transit server can serve schedule tools. Keep device context private behind user-mediated, scoped tool access; do not expose Room or location on an unauthenticated network port. Give a future model structured results from deterministic tools for time, routing and reminders. Validate every proposed action against current data and permission state. Require user confirmation for calendar changes, notifications to contacts and other sensitive actions. Keep the deterministic planner functional without a model.

Benchmark optional Qwen/Gemma/Phi/Llama models on target Android devices before choosing a runtime: model licence, download size, RAM, time to first token, sustained speed, battery and thermal impact, offline behavior and opt-out/deletion. Compare llama.cpp, MediaPipe or ONNX Runtime with the same small retrieval-based task. No large model is bundled or continuously running in this release. Health/sleep context, learned probabilities, live traffic, delays and agentic actions are not implemented or simulated by the MVP.
