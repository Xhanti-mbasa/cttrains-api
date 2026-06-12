/**
 * Hybrid in-memory + Redis cache.
 *
 * Primary layer  — in-memory Map (fast, zero-latency reads).
 * Secondary layer — Redis (persistent across restarts, optional).
 *
 * Set REDIS_URL in .env to enable persistence.
 * Falls back to memory-only automatically if Redis is unavailable.
 */

const REDIS_ENABLED = !!(process.env.REDIS_URL && process.env.REDIS_URL !== '');

const store = new Map(); // in-memory store
let redis = null;        // ioredis client, null if not configured

/**
 * Initialise the Redis connection.
 * Call once at startup. Safe to skip — falls back to memory-only.
 */
async function initRedis() {
  if (!REDIS_ENABLED) {
    console.log('[cache] Redis not configured — using in-memory cache only');
    return;
  }

  try {
    const Redis = require('ioredis');
    redis = new Redis(process.env.REDIS_URL, {
      maxRetriesPerRequest: 2,
      lazyConnect: true,
      enableOfflineQueue: false,
    });

    redis.on('error', (err) => {
      console.warn('[cache] Redis error (falling back to memory):', err.message);
      redis = null;
    });

    await redis.connect();
    console.log('[cache] Redis connected:', process.env.REDIS_URL);
  } catch (err) {
    console.warn('[cache] Redis unavailable (memory-only fallback):', err.message);
    redis = null;
  }
}

/**
 * Store a value in memory and (optionally) Redis.
 * @param {string} key
 * @param {*} value
 * @param {number} ttlSeconds
 */
async function set(key, value, ttlSeconds = 300) {
  const entry = {
    value,
    expiresAt: Date.now() + ttlSeconds * 1000,
    setAt: Date.now(),
  };
  store.set(key, entry);

  if (redis) {
    try {
      await redis.set(key, JSON.stringify(entry), 'EX', ttlSeconds);
    } catch (err) {
      console.warn('[cache] Redis set failed:', err.message);
    }
  }
}

/**
 * Read from memory first; fall back to Redis on a miss.
 * @param {string} key
 * @returns {*|null}  null if missing or expired
 */
async function get(key) {
  // 1. Memory hit
  const memEntry = store.get(key);
  if (memEntry) {
    if (Date.now() > memEntry.expiresAt) {
      store.delete(key);
    } else {
      return memEntry.value;
    }
  }

  // 2. Redis fallback
  if (redis) {
    try {
      const raw = await redis.get(key);
      if (raw) {
        const entry = JSON.parse(raw);
        if (Date.now() <= entry.expiresAt) {
          store.set(key, entry); // warm memory cache
          return entry.value;
        }
      }
    } catch (err) {
      console.warn('[cache] Redis get failed:', err.message);
    }
  }

  return null;
}

/**
 * Delete a key from both stores.
 * @param {string} key
 */
async function del(key) {
  store.delete(key);
  if (redis) {
    try { await redis.del(key); } catch { /* ignore */ }
  }
}

/**
 * Clear all cached entries.
 */
async function flush() {
  store.clear();
  if (redis) {
    try { await redis.flushdb(); } catch { /* ignore */ }
  }
}

/**
 * Returns cache statistics.
 * @returns {Promise<object>}
 */
async function stats() {
  const result = {
    entries: store.size,
    redis_enabled: !!redis,
    memory_entries: store.size,
    redis_info: null,
  };

  if (redis) {
    try {
      result.redis_info = await redis.info('memory');
    } catch { /* ignore */ }
  }

  return result;
}

/** Synchronous size — kept for backwards compatibility */
function size() {
  return store.size;
}

module.exports = { initRedis, set, get, del, flush, size, stats };
