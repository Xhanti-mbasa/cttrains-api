require('dotenv').config();
const express = require('express');
const cors = require('cors');
const cron = require('node-cron');

const linesRouter     = require('./routes/lines');
const stationsRouter  = require('./routes/stations');
const schedulesRouter = require('./routes/schedules');
const updatesRouter   = require('./routes/updates');
const { errorHandler, notFound, rateLimiter, readIntegerSetting } = require('./middleware');
const { scrapeServiceUpdates } = require('./scrapers/cttrains');
const cache = require('./utils/cache');

const app = express();
const PORT = process.env.PORT || 3000;
const proxyHops = readIntegerSetting('TRUST_PROXY_HOPS', 0, 0);
if (proxyHops > 0) app.set('trust proxy', proxyHops);
const allowedOrigins = (process.env.CORS_ALLOWED_ORIGINS || '').split(',').map(origin => origin.trim()).filter(Boolean);

// ── Middleware ──────────────────────────────────────────────────────────────
if (allowedOrigins.length > 0) app.use(cors({ origin: allowedOrigins }));
app.get('/health', (_req, res) => res.json({ status: 'ok' }));
app.use(rateLimiter);
app.use(express.json());

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

// ── Routes ──────────────────────────────────────────────────────────────────
app.use('/lines',     linesRouter);
app.use('/stations',  stationsRouter);
app.use('/schedules', schedulesRouter);
app.use('/updates',   updatesRouter);
app.use('/mobile/v1', require('./routes/mobile'));

// ── 404 / Error ─────────────────────────────────────────────────────────────
app.use(notFound);
app.use(errorHandler);

// ── Background jobs ─────────────────────────────────────────────────────────
// Poll for service updates every 5 minutes during operating hours (05:00–23:00 SAST)
cron.schedule('*/5 5-23 * * 1-6', async () => {
  try {
    const updates = await scrapeServiceUpdates();
    await cache.set('service_updates', updates, 5 * 60);
    if (updates.length > 0) {
      console.log(`[cron] Refreshed ${updates.length} service update(s)`);
    }
  } catch (err) {
    console.warn('[cron] Failed to refresh service updates:', err.message);
  }
}, { timezone: 'Africa/Johannesburg' });

// ── Start ───────────────────────────────────────────────────────────────────
async function start() {
  // Initialise Redis (no-op if REDIS_URL is not set)
  await cache.initRedis();

  app.listen(PORT, () => {
    console.log(`CTTrains API running on http://localhost:${PORT}`);
    console.log('Disclaimer: Unofficial — not affiliated with Metrorail/PRASA');
  });
}

start().catch((err) => {
  console.error('Failed to start server:', err);
  process.exit(1);
});

module.exports = app;
