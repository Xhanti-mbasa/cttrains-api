const { STATUS_CODES } = require('node:http');
const { rateLimit } = require('express-rate-limit');

function errorHandler(err, req, res, next) {
  if (res.headersSent) return next(err);
  const requestedStatus = err.status || err.statusCode;
  const status = Number.isInteger(requestedStatus) && requestedStatus >= 400 && requestedStatus <= 599
    ? requestedStatus
    : 500;
  console.error('[request error]', req.method, status, err.message);
  res.status(status).json({ error: status >= 500 ? 'Internal server error' : STATUS_CODES[status] || 'Request failed' });
}

function notFound(_req, res) {
  res.status(404).json({ error: 'Route not found' });
}

function readIntegerSetting(name, fallback, minimum) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const value = Number(raw);
  if (!/^\d+$/.test(raw) || !Number.isSafeInteger(value) || value < minimum) {
    throw new Error(`${name} must be an integer of at least ${minimum}.`);
  }
  return value;
}

const limit = readIntegerSetting('RATE_LIMIT_RPM', 60, 1);
const rateLimiter = rateLimit({
  windowMs: 60_000,
  limit,
  standardHeaders: 'draft-8',
  legacyHeaders: false,
  handler(req, res) {
    const retryAfter = req.rateLimit.resetTime
      ? Math.max(1, Math.ceil((req.rateLimit.resetTime.getTime() - Date.now()) / 1000))
      : 60;
    res.status(429).json({
      error: `Rate limit exceeded. Maximum ${limit} requests per minute.`,
      retry_after_seconds: retryAfter,
    });
  },
});

module.exports = { errorHandler, notFound, rateLimiter, readIntegerSetting };
