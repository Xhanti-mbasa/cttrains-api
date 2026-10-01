'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const express = require('express');
const { createMobileRouter } = require('../routes/mobile');
const fixture = name => fs.readFileSync(path.join(__dirname, '../../android/core/src/test/resources', `${name}.html`), 'utf8');
async function server(t, schedule = fixture('transfer')) {
  const entries = new Map(); let timetableCalls = 0;
  const router = createMobileRouter({ currentDate: () => '2026-10-01', cacheStore: { get: async key => entries.get(key), set: async (key, value) => entries.set(key, value) },
    readPage: async url => { if (url.endsWith('train-form.php')) return fixture('catalog'); timetableCalls++; return schedule; } });
  const app = express(); app.use('/mobile/v1', router);
  const instance = await new Promise(resolve => { const s = app.listen(0, '127.0.0.1', () => resolve(s)); });
  t.after(() => new Promise(resolve => { instance.closeAllConnections(); instance.close(resolve); }));
  const base = `http://127.0.0.1:${instance.address().port}/mobile/v1`;
  return { get: suffix => fetch(base + suffix), calls: () => timetableCalls };
}
test('HTTP contract retains requested query, separate legs, source and original fetch time', async t => {
  const service = await server(t);
  const catalogue = await (await service.get('/catalog')).json(); assert.ok(catalogue.stations.includes('Unibell'));
  const url = '/journeys?from=Unibell&to=Salt%20River&date=2026-10-01&time=07:00';
  const response = await service.get(url); assert.equal(response.status, 200);
  const data = await response.json();
  assert.deepEqual(data.query, { from: 'Unibell', to: 'Salt River', date: '2026-10-01', time: '07:00' });
  assert.equal(data.sourceId, 'cttrains-api'); assert.ok(data.fetchedAt > 0); assert.equal(data.journeys[0].legs.length, 2);
  const again = await (await service.get(url)).json(); assert.equal(again.fetchedAt, data.fetchedAt); assert.equal(service.calls(), 1);
});
test('HTTP rejects unsupported input before making a timetable request', async t => {
  const service = await server(t);
  const response = await service.get('/journeys?from=Unibell&to=Salt%20River&date=2026-10-01&time=07:07');
  assert.equal(response.status, 400); assert.ok((await response.json()).error); assert.equal(service.calls(), 0);
});
test('HTTP returns an upstream error for unfamiliar markup, not a false empty timetable', async t => {
  const service = await server(t, '<h1>Website moved</h1>');
  const response = await service.get('/journeys?from=Unibell&to=Salt%20River&date=2026-10-01&time=07:00');
  assert.equal(response.status, 502); assert.ok((await response.json()).error);
});
