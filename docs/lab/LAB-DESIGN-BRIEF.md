# CT Trains Lab — design brief and capability map

Status: implementation slice 1 on branch `lab/redesign`. Not published; no Lab release exists.
Prototype: Claude Design canvas "CT Trains Lab — redesign prototype" (private to the owner).
Build and checks: `.github/workflows/android-lab.yml` (no publish job). UI root: `android/app/src/main/kotlin/za/co/cttrains/app/ui/lab/`.

## 1. Commuter jobs this design is built around

1. Get home (or to Work) from wherever I am, now.
2. Find a train I can actually reach on foot, with honest margin.
3. Understand a change before I'm standing on the platform.
4. Save the trip once and be reminded when to leave.
5. Keep working when the network or location is gone.
6. Recover when I miss the ride.

## 2. Sources looked at, and what was taken from them

- **Enrico Tartarotti** (YouTube, e.g. "The 7 Levels of Tech Design"): his videos break product design into levels — visual detail, interaction behaviour, defaults, structure, framing. Taken: decide *defaults and structure* first (what Now shows without input) and treat small visual consistency (corner radii, check styles) as something that changes what people think they can do.
- **Juxtopposed** (YouTube, full-UI redesign videos of YouTube, Spotify, Steam, IMDb, Discord, Twitter, Nintendo Switch 2): each video audits an existing app's screens before rebuilding them. Taken: the method — inventory every existing capability first (section 4), then restructure — rather than any visual style. juxtopposed.com blocks automated reading, so only the published video list was reviewed.
- **Transit (transitapp.com) help docs**: real-time departures carry an explicit wave mark; scheduled times are greyed. Taken: a single, consistent "sched." mark on every time, and no motion near times, because CT Trains has no live feed — animation would imply one.
- **Comparisons of Transit / Citymapper / Moovit / Google Maps reviews**: the most common complaint is scheduled times presented as live. Taken: "~" and "if on time" on every arrival; "Snapshot N h old" when a cached result is shown.
- **Claude Design** (support.claude.com): used for the prototype canvas; implementation follows in Compose.

No other app's assets, names or colours are reproduced. The Lab look ("Platform Ink") is original and avoids Metrorail/PRASA colours and marks; first-use copy states the app is independent.

## 3. Design decisions

- **Three places instead of five tabs + Tools sheet:** Now · Plan · Mine. Now answers "what do I do next" with zero input; Plan is search; Mine holds everything the person owns (places, trips, rides, tools, data, settings) in one flat level.
- **The map is a layer, not a tab.** Opened from Now (map button), any station, a result ("Show on map") or a trip header. Full-screen map, offline areas and station atlas are unchanged in behaviour.
- **Updates & delays is a status strip** on Now/Plan when notices touch the person's lines, plus an entry in Mine. Never hidden behind an icon.
- **Time first.** Departure in condensed 800 weight, tabular figures, never truncated. Origin → destination second. Walk feasibility as a word (Reachable / Tight / Not at walking pace) with a bar, never colour alone.
- **One accent ("Signal" orange) = act now:** leave-by chip, reminder toggle, the recovery switch button. Nothing decorative uses it.
- **Fixed palette, no Material You recolouring** in the Lab edition, so warning and mode colours keep their meaning. Light and dark sets defined; all text ≥ 4.5:1.
- **No perpetual animation**, no shimmer; loading uses static placeholders and offers the saved result after 8 s. Reduced motion = instant changes.
- **Every state has a next action** (offline, location denied, no service on Sunday/holiday, no route, loading, first use, stale widget, empty widget).
- **Widgets:** 2×1 next-trip, 2×1 nearby, 4×2 next-trip; defined behaviour at 200% font (time and leave-by keep size, secondary rows drop), long names (wrap to two lines), stale and empty.

## 4. Navigation — before and after

| Today (regular edition) | CT Trains Lab |
|---|---|
| Bottom bar: Map/Home · Plan · Saved · Journeys · Settings | Bottom bar: Now · Plan · Mine |
| Top bar: Alerts icon, Tools sheet | Status strip on Now/Plan; tools listed in Mine |
| Map tab, map/fullscreen | Map layer from Now, stations, results, trips |
| Settings: one long scroll with nested beta/hosted/profile cards | Mine → Privacy & data, Backup & move, Widgets & cards, etc. — one level |
| Developer options: 7 taps on build label in Settings | Same gesture on the build label in Mine (developer flow owned by ChatGPT, unchanged) |

## 5. Capability map (behaviour contract)

Every row must still work in Lab; "Where" is its new home.

| Capability (today's location) | Where in Lab |
|---|---|
| Train journey planning, source switch, separate caches (Plan; Settings → source) | Plan; source selector in Mine → Privacy & data → Timetable source |
| MyCiTi & Golden Arrow planning/timetables, per-operator switches (Tools → Buses; Settings → Beta) | Plan mode chips; Mine → Buses · beta (switches + timetable browser) |
| Source/fare details, fetch date, fare calculator, trip budget, local price corrections (results; Tools → Fare & budget) | Result cards + trip detail; Mine → Fares & budget |
| Nearby stations / place search (Photon), 5 km nearest-station default (Plan, Map) | Now → Near you; Plan From/To fields |
| Home / Work / School and saved places, map long-press to mark (Saved, My profile) | Mine → place chips; long-press on map layer |
| Favourite stations, saved routes (Saved tab) | Mine → Saved trips (stations listed with routes) |
| Map visibility on Home, online/offline/stations modes, offline area downloads (Map, Settings) | Map layer options; Mine → Offline maps |
| Approximate rail traces, transfer pins, platform notices (map, results) | Trip detail map strip and map layer; "Platform unknown" unless a matched notice exists |
| Leave reminders, backups (1–3, severity), recurrence (Journeys) | Trip detail → Leave reminder / Repeat |
| Missed-ride follow-ups, next departures (notification → ride-departures) | Missed-ride recovery screen |
| Opt-in boarding detection | Trip detail → Detect boarding (off by default) |
| Trip history and correction (Rides) | Mine → Rides (Correct / Confirm) |
| Smart Cards and widgets | Now (Near you + next trip); Mine → Widgets & cards; Lab widgets in its own namespace |
| Door-to-door walking, FOSSGIS consent, walk learning, Health Connect steps | Trip detail walk legs; consent prompts unchanged |
| Journey directions & live notifications | Trip detail → directions button |
| Calendar export (insert intent) | Trip detail → calendar button |
| Safety trip files, import/export, sharing | Trip share button; Mine → Backup & move (import) |
| Community / Matrix chat, private trip sharing (optional) | Mine → Community (off by default) |
| WhatsApp updates (OpenWA reader) and share flows | Mine → WhatsApp; "Tell someone I'm running late" uses share sheet |
| Encrypted document backup / restore; migration from regular edition | Mine → Backup & move; first-use "Restore a backup from CT Trains" |
| Updates & delays (status2.php), filters, 48 h snapshot | Status strip → Updates screen; Mine entry |
| Early train reporter (opens website) | Updates screen footer link |
| App updates | Mine footer → Lab's own experimental channel |
| Privacy: forget location, delete local data, clear timetable cache, theme | Mine → Privacy & data |
| Personal pins/photos, local profile name, optional Google profile sign-in, nearby presence (beta) | Mine → Privacy & data / Community; pins on the map layer |
| Developer options | Build label in Mine (unchanged flow) |

## 6. Separate installation (unchanged plan from the owner's brief)

`CTTRAINS_EXPERIMENTAL=true` → `za.co.cttrains.app.experimental`, "CT Trains Lab", `BuildConfig.EXPERIMENTAL_EDITION`. UI root chooses `ui/lab` when the flag is set. Own database, prefs, Keystore alias and widget providers; no shared UID; migration only via the encrypted backup flow. Release channel `android-experimental-v*`, separate workflow, manifest names its own package; regular updater checks untouched.

## 7. Implementation status

Slice 1 (this branch):

- `MainActivity` chooses `LabTheme { LabApp }` only when `BuildConfig.EXPERIMENTAL_EDITION`; the regular edition's root, screens and identity are unchanged.
- `ui/lab/LabApp.kt`: Now · Plan · Mine navigation. It repeats the regular root's shared effects (Smart Cards refresh, directions position, update checks, developer session refresh, imported files, ride prompts, journey sheet) so behaviour is preserved.
- `ui/lab/LabNow.kt`: next saved trip with leave-by, reachability, Smart Card suggestions, Get home, Near you with explicit one-time location and a permission-denied state, alerts strip (no automatic alert fetch).
- `ui/lab/LabMine.kt`: places, saved routes and stations, one flat list to every tool, a flat Privacy, backup & data page with the regular controls, Widgets & cards, and the seven-tap developer entry.
- `ui/lab/LabDepartures.kt`: missed-ride recovery replacing the regular `ride-departures` list.
- `core/LabTiming.kt` (new, additive, unit tested): reachability, labels, next plan, staleness. No existing engine code changed.
- Plan, trip detail, map and the deeper tools reuse the regular screens under the Lab palette for now.

Next slices: native Lab Plan results and trip detail, Updates screen, map layer controls, widget layouts, then the accessibility/performance review and the `android-experimental-v*` channel with a verified secondary-install flow.

Verification is CI-only (no local Android SDK): `android-lab.yml` builds both editions, runs core tests and lint, checks the Lab APK identity, and runs `LabUiTest` on an emulator with screenshots (light, dark, 200% font, no location, Mine, settings).

## 8. Still open


- Owner review of the three-place navigation and the Platform Ink look.
- Verification still to do on later slices: TalkBack order, 200% font, rotation, small screens, dark/light, reduced motion, offline/low-memory, real reachable journeys and reminders on the emulator CI.
