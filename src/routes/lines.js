const express = require('express');
const router = express.Router();
const { LINES } = require('../data/stations');

/**
 * GET /lines
 * Returns all available lines with metadata.
 */
router.get('/', (req, res) => {
  const lines = Object.values(LINES).map(line => ({
    id: line.id,
    name: line.name,
    description: line.description,
    terminus_a: line.terminus_a,
    terminus_b: line.terminus_b,
    operates_weekdays: line.operates_weekdays,
    operates_saturdays: line.operates_saturdays,
    operates_sundays: line.operates_sundays,
    station_count: line.stations.length,
    ...(line.branches && { branches: line.branches }),
    ...(line.saturday_note && { saturday_note: line.saturday_note }),
    ...(line.weekday_note && { weekday_note: line.weekday_note }),
  }));

  res.json({ count: lines.length, lines });
});

/**
 * GET /lines/:lineId
 * Returns a single line with its full station list.
 */
router.get('/:lineId', (req, res) => {
  const line = LINES[req.params.lineId];
  if (!line) {
    return res.status(404).json({ error: `Line '${req.params.lineId}' not found.`, available: Object.keys(LINES) });
  }
  res.json(line);
});

/**
 * GET /lines/:lineId/stations
 * Returns the ordered station list for a line.
 */
router.get('/:lineId/stations', (req, res) => {
  const line = LINES[req.params.lineId];
  if (!line) {
    return res.status(404).json({ error: `Line '${req.params.lineId}' not found.`, available: Object.keys(LINES) });
  }

  const stations = line.stations.map((name, index) => ({
    index,
    id: name.toLowerCase().replace(/\s+/g, '_').replace(/'/g, ''),
    name,
  }));

  res.json({ line: line.id, line_name: line.name, count: stations.length, stations });
});

module.exports = router;
