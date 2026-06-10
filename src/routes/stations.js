const express = require('express');
const router = express.Router();
const { ALL_STATIONS, buildStationObject } = require('../data/stations');

/**
 * GET /stations
 * Returns the master station list with line memberships.
 * Supports optional ?q= filter for name search.
 */
router.get('/', (req, res) => {
  const { q } = req.query;

  let stations = ALL_STATIONS.map(buildStationObject);

  if (q) {
    const query = q.toLowerCase().trim();
    stations = stations.filter(s => s.name.toLowerCase().includes(query));
  }

  res.json({ count: stations.length, stations });
});

/**
 * GET /stations/:stationId
 * Returns a single station by its ID (snake_case name).
 */
router.get('/:stationId', (req, res) => {
  const id = req.params.stationId.toLowerCase();
  const match = ALL_STATIONS.find(
    name => name.toLowerCase().replace(/\s+/g, '_').replace(/'/g, '') === id
  );

  if (!match) {
    return res.status(404).json({ error: `Station '${req.params.stationId}' not found.` });
  }

  res.json(buildStationObject(match));
});

module.exports = router;
