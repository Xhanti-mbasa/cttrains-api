/**
 * Timetable Snapshot Utility
 *
 * Saves daily timetable snapshots to disk and provides change-detection
 * between two snapshot dates (additions, removals, modifications).
 *
 * Snapshots are stored in .timetable-snapshots/<lineId>_<date>.json
 * relative to the project root.
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const SNAPSHOT_DIR = path.join(__dirname, '../../.timetable-snapshots');

/**
 * Ensure the snapshot directory exists.
 */
function ensureDir() {
  if (!fs.existsSync(SNAPSHOT_DIR)) {
    fs.mkdirSync(SNAPSHOT_DIR, { recursive: true });
  }
}

/**
 * Build the file path for a snapshot.
 * @param {string} lineId
 * @param {string} date  YYYY-MM-DD
 * @returns {string}
 */
function snapshotPath(lineId, date) {
  return path.join(SNAPSHOT_DIR, `${lineId}_${date}.json`);
}

/**
 * Hash a timetable array for quick equality checks.
 * @param {object[]} timetable
 * @returns {string}
 */
function hashTimetable(timetable) {
  const canonical = JSON.stringify(
    timetable.map(t => ({
      train_number: t.train_number,
      departure_time: t.departure_time,
      arrival_time: t.arrival_time,
      platform: t.platform ?? null,
    })).sort((a, b) => (a.train_number || '').localeCompare(b.train_number || ''))
  );
  return crypto.createHash('sha256').update(canonical).digest('hex').slice(0, 16);
}

/**
 * Save a timetable snapshot for a given line and date.
 *
 * @param {string}   lineId     e.g. 'northern'
 * @param {string}   date       YYYY-MM-DD
 * @param {object[]} timetable  Array of schedule entries
 * @returns {Promise<object>}   The saved snapshot object
 */
async function saveSnapshot(lineId, date, timetable) {
  ensureDir();

  const snapshot = {
    line: lineId,
    date,
    captured_at: new Date().toISOString(),
    hash: hashTimetable(timetable),
    train_count: timetable.length,
    timetable,
  };

  fs.writeFileSync(snapshotPath(lineId, date), JSON.stringify(snapshot, null, 2));
  console.log(`[snapshot] Saved ${lineId} ${date} (${timetable.length} trains)`);
  return snapshot;
}

/**
 * Load a snapshot for a given line and date.
 *
 * @param {string} lineId
 * @param {string} date   YYYY-MM-DD
 * @returns {object|null}  Snapshot object, or null if not found
 */
async function loadSnapshot(lineId, date) {
  const filepath = snapshotPath(lineId, date);
  if (!fs.existsSync(filepath)) return null;
  try {
    return JSON.parse(fs.readFileSync(filepath, 'utf8'));
  } catch {
    return null;
  }
}

/**
 * Detect changes between two timetable arrays.
 *
 * @param {object[]} oldTimetable
 * @param {object[]} newTimetable
 * @returns {object}  Change summary
 */
function detectChanges(oldTimetable, newTimetable) {
  const oldMap = new Map(oldTimetable.map(t => [t.train_number, t]));
  const newMap = new Map(newTimetable.map(t => [t.train_number, t]));

  const added = [];
  const removed = [];
  const modified = [];
  const details = [];

  for (const [num, entry] of newMap) {
    if (!oldMap.has(num)) {
      added.push(num);
      details.push({ type: 'added', train_number: num, ...entry });
    } else {
      const old = oldMap.get(num);
      const changes = [];
      if (old.departure_time !== entry.departure_time)
        changes.push(`departure: ${old.departure_time} → ${entry.departure_time}`);
      if (old.arrival_time !== entry.arrival_time)
        changes.push(`arrival: ${old.arrival_time} → ${entry.arrival_time}`);
      if ((old.platform ?? null) !== (entry.platform ?? null))
        changes.push(`platform: ${old.platform} → ${entry.platform}`);
      if (changes.length > 0) {
        modified.push(num);
        details.push({ type: 'modified', train_number: num, changes });
      }
    }
  }

  for (const [num, entry] of oldMap) {
    if (!newMap.has(num)) {
      removed.push(num);
      details.push({ type: 'removed', train_number: num, ...entry });
    }
  }

  const changed = added.length > 0 || removed.length > 0 || modified.length > 0;
  return {
    changed,
    reason: changed ? 'timetable_changed' : 'no_change',
    old_count: oldTimetable.length,
    new_count: newTimetable.length,
    added,
    removed,
    modified,
    details,
  };
}

/**
 * Compare two snapshots for a given line across two dates.
 *
 * @param {string} lineId
 * @param {string} dateA  YYYY-MM-DD (older)
 * @param {string} dateB  YYYY-MM-DD (newer)
 * @returns {Promise<object>}
 */
async function compareSnapshots(lineId, dateA, dateB) {
  const snapA = await loadSnapshot(lineId, dateA);
  const snapB = await loadSnapshot(lineId, dateB);

  if (!snapA) return { error: `Snapshot not found: ${lineId} ${dateA}` };
  if (!snapB) return { error: `Snapshot not found: ${lineId} ${dateB}` };

  if (snapA.hash === snapB.hash) {
    return {
      line: lineId,
      date_a: dateA,
      date_b: dateB,
      changes: { changed: false, reason: 'no_change', old_count: snapA.train_count, new_count: snapB.train_count, added: [], removed: [], modified: [], details: [] },
    };
  }

  return {
    line: lineId,
    date_a: dateA,
    date_b: dateB,
    changes: detectChanges(snapA.timetable, snapB.timetable),
  };
}

/**
 * List all available snapshot dates for a given line.
 *
 * @param {string} lineId
 * @returns {string[]}  Sorted array of YYYY-MM-DD date strings
 */
function listSnapshots(lineId) {
  ensureDir();
  const prefix = `${lineId}_`;
  return fs.readdirSync(SNAPSHOT_DIR)
    .filter(f => f.startsWith(prefix) && f.endsWith('.json'))
    .map(f => f.slice(prefix.length, -5))
    .sort();
}

/**
 * Remove snapshots older than daysToKeep for all lines.
 *
 * @param {number} daysToKeep  Default 30
 */
function cleanupOldSnapshots(daysToKeep = 30) {
  ensureDir();
  const cutoff = Date.now() - daysToKeep * 24 * 60 * 60 * 1000;
  let removed = 0;

  fs.readdirSync(SNAPSHOT_DIR)
    .filter(f => f.endsWith('.json'))
    .forEach(f => {
      const filepath = path.join(SNAPSHOT_DIR, f);
      const stat = fs.statSync(filepath);
      if (stat.mtimeMs < cutoff) {
        fs.unlinkSync(filepath);
        removed++;
      }
    });

  if (removed > 0) console.log(`[snapshot] Cleaned up ${removed} old snapshot(s)`);
}

module.exports = {
  saveSnapshot,
  loadSnapshot,
  detectChanges,
  compareSnapshots,
  listSnapshots,
  cleanupOldSnapshots,
};
