/**
 * Global error handler middleware.
 */
function errorHandler(err, req, res, next) {
  console.error(`[error] ${req.method} ${req.path} —`, err.message);
  res.status(err.status || 500).json({
    error: err.message || 'Internal server error',
    path: req.path,
  });
}

/**
 * 404 handler for unmatched routes.
 */
function notFound(req, res) {
  res.status(404).json({
    error: `Route not found: ${req.method} ${req.path}`,
    docs: '/api-docs',
  });
}

/**
 * Lightweight in-process rate limiter (per IP, per minute).
 * Not a replacement for a proper API gateway but keeps scrapers polite.
 */
const RATE_LIMIT = parseInt(process.env.RATE_LIMIT_RPM || '60', 10);
const ipHits = new Map();

setInterval(() => ipHits.clear(), 60 * 1000); // reset every minute

function rateLimiter(req, res, next) {
  const ip = req.ip || req.socket.remoteAddress;
  const hits = (ipHits.get(ip) || 0) + 1;
  ipHits.set(ip, hits);

  if (hits > RATE_LIMIT) {
    return res.status(429).json({
      error: 'Rate limit exceeded. Maximum 60 requests per minute.',
      retry_after_seconds: 60,
    });
  }
  next();
}

module.exports = { errorHandler, notFound, rateLimiter };
