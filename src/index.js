require('dotenv').config();
const express = require('express');
const cors = require('cors');
const cron = require('node-cron');

const linesRouter     = require('./routes/lines');
const stationsRouter  = require('./routes/stations');
const schedulesRouter = require('./routes/schedules');
const updatesRouter   = require('./routes/updates');
const { errorHandler, notFound, rateLimiter } = require('./middleware');
const { scrapeServiceUpdates } = require('./scrapers/cttrains');
const cache = require('./utils/cache');

const app = express();
const PORT = process.env.PORT || 3000;

// ── Middleware ──────────────────────────────────────────────────────────────
app.use(cors());
app.use(express.json());
app.use(rateLimiter);

// ── Root ────────────────────────────────────────────────────────────────────
app.get('/', (req, res) => {
  res.json({
    name: 'CTTrains API',
    description: 'Unofficial REST API for Cape Town Metrorail timetables and service updates.',
    version: '1.0.0',
    disclaimer: 'This API is not affiliated with Metrorail or PRASA. Data sourced from cttrains.co.za — a free community service.',
    endpoints: {
      lines:     'GET /lines',
      line:      'GET /lines/:lineId',
      stations_for_line: 'GET /lines/:lineId/stations',
      stations:  'GET /stations?q=',
      station:   'GET /stations/:stationId',
      schedules: 'GET /schedules?from=&to=&date=&time=',
      line_timetable: 'GET /schedules/lines/:lineId?from=&to=&days=&search_by=&time=',
      updates:   'GET /updates?line=',
      health:    'GET /health',
    },
    source: 'https://github.com/Xhanti-mbasa/cttrains-api',
    data_source: 'https://cttrains.co.za',
  });
});

// ── Health ──────────────────────────────────────────────────────────────────
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    uptime_seconds: Math.floor(process.uptime()),
    cache_entries: cache.size(),
    timestamp: new Date().toISOString(),
  });
});

// ── Routes ──────────────────────────────────────────────────────────────────
app.use('/lines',     linesRouter);
app.use('/stations',  stationsRouter);
app.use('/schedules', schedulesRouter);
app.use('/updates',   updatesRouter);

// ── 404 / Error ─────────────────────────────────────────────────────────────
app.use(notFound);
app.use(errorHandler);

// ── Background jobs ─────────────────────────────────────────────────────────
// Poll for service updates every 5 minutes during operating hours (05:00–23:00 SAST)
cron.schedule('*/5 5-23 * * 1-6', async () => {
  try {
    const updates = await scrapeServiceUpdates();
    cache.set('service_updates', updates, 5 * 60);
    if (updates.length > 0) {
      console.log(`[cron] Refreshed ${updates.length} service update(s)`);
    }
  } catch (err) {
    console.warn('[cron] Failed to refresh service updates:', err.message);
  }
}, { timezone: 'Africa/Johannesburg' });

// ── Start ───────────────────────────────────────────────────────────────────
app.listen(PORT, () => {
  console.log(`CTTrains API running on http://localhost:${PORT}`);
  console.log('Disclaimer: Unofficial — not affiliated with Metrorail/PRASA');
});

module.exports = app;
