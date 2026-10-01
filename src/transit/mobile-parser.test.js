'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { parseCatalog, parseJourneys, parseFares, validateQuery } = require('./mobile-parser');
const fixture = name => fs.readFileSync(path.join(__dirname, '../../android/core/src/test/resources', `${name}.html`), 'utf8');
test('catalogue retains source stations and holiday policy', () => {
  const c = parseCatalog(fixture('catalog'));
  assert.ok(c.stations.includes('Unibell')); assert.ok(c.stations.includes('Salt River')); assert.ok(c.stations.length > 90);
});
test('direct services preserve all stops and do not wrap to earlier trains', () => {
  const rows = parseJourneys(fixture('direct'), '07:00');
  assert.ok(rows.length); assert.ok(rows.every(j => j.legs.length === 1 && j.legs[0].stops.length > 2));
  assert.ok(rows.every(j => j.legs[0].stops[0].time >= '07:00'));
});
test('transfer journeys retain separate trains and connection station', () => {
  const rows = parseJourneys(fixture('transfer'), '07:00');
  assert.ok(rows.length); const first = rows[0]; assert.equal(first.legs.length, 2);
  assert.equal(first.legs[0].stops.at(-1).name, first.legs[1].stops[0].name);
  assert.notEqual(first.legs[0].train, first.legs[1].train);
});
test('unknown HTML is an error, an explicit no-results message is empty', () => {
  assert.throws(() => parseJourneys('<h1>Service moved</h1>', '07:00'));
  assert.deepEqual(parseJourneys('<p>No trains found</p>', '07:00'), []);
});
test('missing train information is rejected', () => {
  assert.throws(() => parseJourneys(fixture('transfer').replace(/Train number:/g, 'Service:'), '07:00'));
});
test('fares only come from the source', () => {
  assert.equal(parseFares('<p>No fares published</p>'), null);
  assert.equal(parseFares('<p>Fares: Single R12</p>'), 'Fares: Single R12');
});
test('platform fields belong to one stop and never leak across a leg', () => {
  const source = fixture('transfer').replace('class="flex items-center"', 'class="flex items-center" data-platform="2B"');
  const stops = parseJourneys(source, '07:00')[0].legs.flatMap(leg => leg.stops);
  assert.equal(stops[0].platform, '2B');
  assert.ok(stops.slice(1).every(stop => !Object.hasOwn(stop, 'platform')));
});
test('query validation rejects invalid dates, unsupported days and time intervals', () => {
  const c = { stations: ['Unibell', 'Salt River'], holidays: ['2026-10-02'] };
  const q = { from: 'Unibell', to: 'Salt River', date: '2026-10-01', time: '07:00' };
  assert.equal(validateQuery(q, c, '2026-10-01'), null);
  for (const edit of [{ date: '2026-02-31' }, { date: '2026-10-04' }, { date: '2026-10-02' }, { date: '2026-12-01' }, { time: '07:07' }, { from: 'Unknown' }, { from: ['Unibell'] }]) assert.ok(validateQuery({ ...q, ...edit }, c, '2026-10-01'));
});
