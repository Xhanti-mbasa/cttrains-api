/**
 * Lightweight in-memory TTL cache.
 * Keeps scraped data fresh without hammering cttrains.co.za on every request.
 */

const store = new Map();

/**
 * @param {string} key
 * @param {*} value
 * @param {number} ttlSeconds
 */
function set(key, value, ttlSeconds = 300) {
  store.set(key, {
    value,
    expiresAt: Date.now() + ttlSeconds * 1000,
  });
}

/**
 * @param {string} key
 * @returns {*|null}  null if missing or expired
 */
function get(key) {
  const entry = store.get(key);
  if (!entry) return null;
  if (Date.now() > entry.expiresAt) {
    store.delete(key);
    return null;
  }
  return entry.value;
}

function del(key) {
  store.delete(key);
}

function flush() {
  store.clear();
}

function size() {
  return store.size;
}

module.exports = { set, get, del, flush, size };
