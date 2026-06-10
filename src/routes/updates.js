const express = require('express');
const router = express.Router();
const { scrapeServiceUpdates } = require('../scrapers/cttrains');
const cache = require('../utils/cache');

const UPDATES_TTL = 5 * 60; // 5 minutes — updates are time-sensitive
const CACHE_KEY = 'service_updates';

/**
 * GET /updates
 * Returns current service disruptions, delays, and cancellations.
 *
 * Query params:
 *   line  - filter by line ID (optional)
 *
 * Example: GET /updates
 * Example: GET /updates?line=southern
 */
router.get('/', async (req, res) => {
  const { line } = req.query;

  const cached = cache.get(CACHE_KEY);
  let updates;

  if (cached) {
    updates = cached;
  } else {
    try {
      updates = await scrapeServiceUpdates();
      cache.set(CACHE_KEY, updates, UPDATES_TTL);
    } catch (err) {
      console.error('[updates]', err.message);
      return res.status(502).json({
        error: 'Failed to fetch service updates from cttrains.co.za.',
        detail: err.message,
        source: 'https://cttrains.co.za/status2.php',
      });
    }
  }

  const filtered = line
    ? updates.filter(u => u.line === line.toLowerCase())
    : updates;

  res.json({
    count: filtered.length,
    fetched_at: new Date().toISOString(),
    cache_ttl_seconds: UPDATES_TTL,
    ...(line && { line_filter: line }),
    updates: filtered,
    source: 'https://cttrains.co.za/status2.php',
    disclaimer: 'CTTrains monitors Metrorail public channels. Updates may be delayed or incomplete.',
  });
});

module.exports = router;
