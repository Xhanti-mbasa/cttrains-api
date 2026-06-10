const express = require('express');
const router = express.Router();
const { scrapeSchedule, scrapeLineTimetable } = require('../scrapers/cttrains');
const { checkServiceSuspension } = require('../utils/holidays');
const cache = require('../utils/cache');
const { LINES, ALL_STATIONS } = require('../data/stations');

const SCHEDULE_TTL = 60 * 60; // 1 hour — timetables rarely change intraday

/**
 * Validates that a station name exists in the master list.
 */
function resolveStation(name) {
  if (!name) return null;
  const n = name.trim().toLowerCase();
  return ALL_STATIONS.find(s => s.toLowerCase() === n) || null;
}

/**
 * GET /schedules
 * Cross-line station-to-station schedule search.
 *
 * Query params:
 *   from      - departure station name (required)
 *   to        - arrival station name (required)
 *   date      - travel date YYYY-MM-DD (required)
 *   time      - departure time HH:MM (optional, default 06:00)
 *
 * Example: GET /schedules?from=Cape+Town&to=Muizenberg&date=2026-06-11&time=07:30
 */
router.get('/', async (req, res) => {
  const { from, to, date, time } = req.query;

  if (!from || !to || !date) {
    return res.status(400).json({
      error: 'Missing required parameters.',
      required: ['from', 'to', 'date'],
      optional: ['time (HH:MM, default 06:00)'],
      example: '/schedules?from=Cape+Town&to=Muizenberg&date=2026-06-11&time=07:30',
    });
  }

  const fromStation = resolveStation(from);
  const toStation = resolveStation(to);

  if (!fromStation) return res.status(400).json({ error: `Unknown departure station: '${from}'` });
  if (!toStation)   return res.status(400).json({ error: `Unknown arrival station: '${to}'` });
  if (fromStation === toStation) return res.status(400).json({ error: 'Departure and arrival stations cannot be the same.' });

  // Date validation
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    return res.status(400).json({ error: 'Invalid date format. Use YYYY-MM-DD.' });
  }

  const suspension = checkServiceSuspension(date);
  if (suspension.suspended) {
    return res.json({
      service_suspended: true,
      reason: suspension.reason,
      from: fromStation,
      to: toStation,
      date,
      schedules: [],
    });
  }

  const cacheKey = `schedule:${fromStation}:${toStation}:${date}:${time || '06:00'}`;
  const cached = cache.get(cacheKey);
  if (cached) return res.json({ ...cached, cached: true });

  try {
    const schedules = await scrapeSchedule({ from: fromStation, to: toStation, date, time: time || '06:00' });

    const result = {
      from: fromStation,
      to: toStation,
      date,
      requested_time: time || '06:00',
      count: schedules.length,
      schedules,
      source: 'https://cttrains.co.za',
      disclaimer: 'CTTrains is an independent community service — not affiliated with Metrorail. Schedule accuracy not guaranteed.',
    };

    cache.set(cacheKey, result, SCHEDULE_TTL);
    res.json(result);
  } catch (err) {
    console.error('[schedules]', err.message);
    res.status(502).json({
      error: 'Failed to fetch schedule from cttrains.co.za.',
      detail: err.message,
    });
  }
});

/**
 * GET /schedules/lines/:lineId
 * Line-specific timetable between two stations.
 *
 * Query params:
 *   from       - departure station (required)
 *   to         - arrival station (required)
 *   days       - 'weekday' | 'saturday' (default: weekday)
 *   search_by  - 'departure' | 'arrival' | 'all' (default: departure)
 *   time       - HH:MM (default: 06:00)
 *
 * Example: GET /schedules/lines/southern?from=Cape+Town&to=Fish+Hoek&days=saturday
 */
router.get('/lines/:lineId', async (req, res) => {
  const { lineId } = req.params;
  const { from, to, days = 'weekday', search_by = 'departure', time = '06:00' } = req.query;

  if (!LINES[lineId]) {
    return res.status(404).json({ error: `Unknown line '${lineId}'.`, available: Object.keys(LINES) });
  }

  if (!from || !to) {
    return res.status(400).json({
      error: 'Missing required parameters.',
      required: ['from', 'to'],
      optional: ['days (weekday|saturday)', 'search_by (departure|arrival|all)', 'time (HH:MM)'],
    });
  }

  const lineStations = LINES[lineId].stations;
  const fromStation = lineStations.find(s => s.toLowerCase() === from.trim().toLowerCase());
  const toStation   = lineStations.find(s => s.toLowerCase() === to.trim().toLowerCase());

  if (!fromStation) return res.status(400).json({ error: `Station '${from}' is not on the ${lineId} line.`, stations: lineStations });
  if (!toStation)   return res.status(400).json({ error: `Station '${to}' is not on the ${lineId} line.`, stations: lineStations });
  if (fromStation === toStation) return res.status(400).json({ error: 'Departure and arrival stations cannot be the same.' });

  const cacheKey = `line:${lineId}:${fromStation}:${toStation}:${days}:${search_by}:${time}`;
  const cached = cache.get(cacheKey);
  if (cached) return res.json({ ...cached, cached: true });

  try {
    const schedules = await scrapeLineTimetable({ line: lineId, from: fromStation, to: toStation, days, searchBy: search_by, time });

    const result = {
      line: lineId,
      line_name: LINES[lineId].name,
      from: fromStation,
      to: toStation,
      days,
      search_by,
      requested_time: time,
      count: schedules.length,
      schedules,
      source: 'https://cttrains.co.za',
    };

    cache.set(cacheKey, result, SCHEDULE_TTL);
    res.json(result);
  } catch (err) {
    console.error('[schedules/lines]', err.message);
    res.status(502).json({
      error: `Failed to fetch timetable for line '${lineId}' from cttrains.co.za.`,
      detail: err.message,
    });
  }
});

module.exports = router;
