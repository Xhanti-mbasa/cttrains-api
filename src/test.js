/**
 * Basic smoke tests — no test framework needed, just node src/test.js
 */

const { LINES, ALL_STATIONS, buildStationObject, getStationLines } = require('./data/stations');
const { checkServiceSuspension } = require('./utils/holidays');
const cache = require('./utils/cache');

let passed = 0;
let failed = 0;

function assert(label, condition, detail = '') {
  if (condition) {
    console.log(`  ✓ ${label}`);
    passed++;
  } else {
    console.error(`  ✗ ${label}${detail ? ' — ' + detail : ''}`);
    failed++;
  }
}

console.log('\n── Station data ───────────────────────────────────────');
assert('102 stations in master list', ALL_STATIONS.length === 102, `got ${ALL_STATIONS.length}`);
assert('Cape Town exists', ALL_STATIONS.includes('Cape Town'));
assert('Simonstown exists', ALL_STATIONS.includes('Simonstown'));
// Cape Town is the terminus on all 5 lines (including monte_vista which branches from northern)
assert('Cape Town is on 5 lines', getStationLines('Cape Town').length === 5, `got ${getStationLines('Cape Town').length}`);
assert('Simonstown is only on southern', JSON.stringify(getStationLines('Simonstown')) === JSON.stringify(['southern']));

const ct = buildStationObject('Cape Town');
assert('buildStationObject id', ct.id === 'cape_town');
assert('buildStationObject name', ct.name === 'Cape Town');
assert('buildStationObject lines array', Array.isArray(ct.lines));

console.log('\n── Line data ──────────────────────────────────────────');
assert('5 lines defined', Object.keys(LINES).length === 5);
assert('southern has 28 stations', LINES.southern.stations.length === 28);
assert('southern operates weekdays', LINES.southern.operates_weekdays === true);
assert('southern operates saturdays', LINES.southern.operates_saturdays === true);
assert('southern no sundays', LINES.southern.operates_sundays === false);

console.log('\n── Holiday / suspension checks ────────────────────────');
const sunday = checkServiceSuspension('2026-06-14');
assert('Sunday is suspended', sunday.suspended === true);
assert('Sunday reason mentions Sunday', /sunday/i.test(sunday.reason));

const christmasDay = checkServiceSuspension('2026-12-25');
assert('Christmas Day is suspended', christmasDay.suspended === true);

const monday = checkServiceSuspension('2026-06-15');
assert('Regular Monday is not suspended', monday.suspended === false);

const humanRightsDay = checkServiceSuspension('2026-03-21');
assert('Human Rights Day is suspended', humanRightsDay.suspended === true);

console.log('\n── Cache ──────────────────────────────────────────────');
cache.set('test_key', { value: 42 }, 5);
assert('cache set and get', cache.get('test_key')?.value === 42);
cache.del('test_key');
assert('cache delete', cache.get('test_key') === null);
cache.set('ttl_test', 'hello', 0);
setTimeout(() => {
  assert('cache TTL expiry (0s)', cache.get('ttl_test') === null);

  console.log(`\n── Results ────────────────────────────────────────────`);
  console.log(`  Passed: ${passed + (cache.get('ttl_test') === null ? 1 : 0)}`);
  if (failed > 0) {
    console.error(`  Failed: ${failed}`);
    process.exit(1);
  } else {
    console.log('  All tests passed ✓');
  }
}, 10);
