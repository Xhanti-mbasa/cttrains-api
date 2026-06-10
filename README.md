# CTTrains API

Unofficial REST API for Cape Town Metrorail timetables and service updates, built on top of [cttrains.co.za](https://cttrains.co.za) — a free community service run since 2011.

> **Disclaimer:** This project is not affiliated with Metrorail or PRASA. All data is sourced from cttrains.co.za. Schedule accuracy is not guaranteed. Do not use for safety-critical decisions.

---

## Stack

- **Runtime:** Node.js 22+
- **Framework:** Express
- **Scraping:** Axios + Cheerio
- **Background jobs:** node-cron (service update polling)

---

## Project Structure

```
src/
├── index.js              # Express app + cron jobs
├── data/
│   └── stations.js       # All station + line seed data
├── scrapers/
│   └── cttrains.js       # HTML scraper for cttrains.co.za
├── routes/
│   ├── lines.js          # GET /lines
│   ├── stations.js       # GET /stations
│   ├── schedules.js      # GET /schedules
│   └── updates.js        # GET /updates
├── middleware/
│   └── index.js          # Error handling, rate limiting
├── utils/
│   ├── cache.js          # In-memory TTL cache
│   └── holidays.js       # SA public holiday checker
└── test.js               # Smoke tests (no dependencies)
```

---

## Setup

```bash
git clone https://github.com/Xhanti-mbasa/cttrains-api.git
cd cttrains-api
npm install
cp .env.example .env
npm start
```

Dev mode (auto-restart on file change, Node 22+):

```bash
npm run dev
```

---

## API Reference

Base URL: `http://localhost:3000`

### `GET /`
API root — returns endpoint map.

---

### `GET /health`
Returns uptime and cache stats.

```json
{
  "status": "ok",
  "uptime_seconds": 120,
  "cache_entries": 4,
  "timestamp": "2026-06-10T08:00:00.000Z"
}
```

---

### `GET /lines`
All lines with metadata.

```json
{
  "count": 5,
  "lines": [
    {
      "id": "southern",
      "name": "Southern Line",
      "terminus_a": "Cape Town",
      "terminus_b": "Simonstown",
      "operates_weekdays": true,
      "operates_saturdays": true,
      "operates_sundays": false,
      "station_count": 28
    }
  ]
}
```

---

### `GET /lines/:lineId`
Full line detail including station list.

**Line IDs:** `southern` | `cape_flats` | `central` | `northern` | `monte_vista`

---

### `GET /lines/:lineId/stations`
Ordered station list for a line.

```json
{
  "line": "southern",
  "line_name": "Southern Line",
  "count": 28,
  "stations": [
    { "index": 0, "id": "cape_town", "name": "Cape Town" },
    { "index": 1, "id": "woodstock", "name": "Woodstock" }
  ]
}
```

---

### `GET /stations`
Master list of all 102 stations with their line memberships.

| Param | Type   | Description              |
|-------|--------|--------------------------|
| `q`   | string | Filter by name substring |

```bash
GET /stations?q=fish
```

```json
{
  "count": 1,
  "stations": [
    { "id": "fish_hoek", "name": "Fish Hoek", "lines": ["southern"] }
  ]
}
```

---

### `GET /stations/:stationId`
Single station by ID (snake_case name).

```bash
GET /stations/muizenberg
```

---

### `GET /schedules`
Cross-line station-to-station schedule search.

| Param  | Required | Description                    |
|--------|----------|--------------------------------|
| `from` | ✓        | Departure station name         |
| `to`   | ✓        | Arrival station name           |
| `date` | ✓        | Travel date `YYYY-MM-DD`       |
| `time` | –        | Departure time `HH:MM` (default `06:00`) |

```bash
GET /schedules?from=Cape+Town&to=Muizenberg&date=2026-06-11&time=07:30
```

Returns `service_suspended: true` with a reason on Sundays and public holidays.

---

### `GET /schedules/lines/:lineId`
Line-specific timetable between two stations.

| Param       | Description                                     |
|-------------|-------------------------------------------------|
| `from`      | Departure station (must be on this line)        |
| `to`        | Arrival station (must be on this line)          |
| `days`      | `weekday` (default) or `saturday`               |
| `search_by` | `departure` (default) / `arrival` / `all`       |
| `time`      | `HH:MM` (default `06:00`)                       |

```bash
GET /schedules/lines/southern?from=Cape+Town&to=Fish+Hoek&days=saturday
```

---

### `GET /updates`
Current service disruptions, delays, and cancellations scraped from cttrains.co.za.

| Param  | Description                    |
|--------|--------------------------------|
| `line` | Filter by line ID (optional)   |

```bash
GET /updates?line=southern
```

```json
{
  "count": 2,
  "fetched_at": "2026-06-10T07:55:00.000Z",
  "cache_ttl_seconds": 300,
  "updates": [
    {
      "id": "upd_1749542100000_0",
      "type": "truncation",
      "line": "southern",
      "train_number": "T0127",
      "delay_min": 15,
      "delay_max": 20,
      "raw_message": "T0127 - 08:30 from Cape Town to Retreat will terminate at Wynberg...",
      "published_at": "2026-06-10T07:55:00.000Z"
    }
  ]
}
```

Updates are polled automatically every 5 minutes on weekdays/Saturdays between 05:00–23:00 SAST.

---

## Caching

| Endpoint   | TTL        |
|------------|------------|
| Schedules  | 1 hour     |
| Timetables | 1 hour     |
| Updates    | 5 minutes  |
| Stations   | Static (in-memory, no TTL) |

---

## Rate Limiting

60 requests per minute per IP. Configurable via `RATE_LIMIT_RPM` env var.

---

## Environment Variables

| Variable         | Default | Description                     |
|------------------|---------|---------------------------------|
| `PORT`           | `3000`  | HTTP port                       |
| `RATE_LIMIT_RPM` | `60`    | Max requests per minute per IP  |

---

## Running Tests

```bash
npm test
```

No external test runner — plain Node.js smoke tests covering station data, holiday logic, and caching.

---

## Notes on Scraping

The scraper POSTs to cttrains.co.za form endpoints and parses the HTML response with Cheerio. If the site's HTML structure changes, `src/scrapers/cttrains.js` will need updating. The `parseScheduleResults` function includes a regex fallback for resilience.

The User-Agent header identifies this as the cttrains-api community tool.

---

## License

MIT
