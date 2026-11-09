# Bundled railway geometry

`app/src/main/assets/rail-network.json` is a compact derived OpenStreetMap database, licensed under **ODbL 1.0**, separately from the repository's MIT-licensed code. Attribution: **© OpenStreetMap contributors**. The complete derived database and its importer are available in this repository. See [OpenStreetMap copyright](https://www.openstreetmap.org/copyright) and the [ODbL licence](https://opendatacommons.org/licenses/odbl/1-0/).

The source snapshot, exact bounded query, endpoint, counts, station-anchor distances and limitations are recorded in [rail-provenance.json](rail-provenance.json). The 1 October 2026 snapshot contains 15,559 nodes, 15,775 connections and rail anchors within 400 metres for all 102 bundled stations. A station anchor is an approximate display connection to mapped rail geometry, not a platform location.

The importer preserves OSM node identities. Ways are connected only through a shared node, so nearby or grade-separated tracks are not automatically joined. Yard/siding/spur and explicitly industrial ways are excluded by the import query. The client considers up to 24 nearby rail-node candidates within 250 metres at each station, then computes an undirected shortest path separately for each consecutive timetable stop pair. This avoids unnecessarily looping back from a single nearest parallel track. Actual shared OSM node IDs still govern connectivity; candidate selection does not join tracks. The route retains mapped track shape and the timetable's stop order. Implausibly long detours, missing anchors and disconnected tracks fall back to a visibly dashed approximate link between known stations. Unknown positions never create a bridge.

This is a **route visualization**, not an official operational train path, track direction, signalling plan, confirmation of service, or walking route between platforms. Parallel-track selection and gaps can remain imperfect. Platform numbers come only from explicit timetable stop fields or narrowly matched, fresh boarding notices; they are never inferred from railway geometry or train colours.

## Refresh the snapshot

Fetch the query from `rail-provenance.json` once from a documented Overpass instance. Do not run this import on each app launch or CI build. The bundled file works offline and needs no runtime routing service or key.

```bash
# Repository root; the JSON must include complete way node IDs and geometry.
python3 android/scripts/import-rail-network.py /path/to/overpass-response.json
```

Review the source licence, snapshot timestamp, bounds, import report and changed database. Run the Kotlin tests, including the captured transfer-journey coverage check, before publishing the refreshed data. Overpass partial-result remarks are rejected by the importer.
