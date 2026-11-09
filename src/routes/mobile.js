'use strict';
const express = require('express');
const axios = require('axios');
const cache = require('../utils/cache');
const { parseCatalog, parseJourneys, parseFares, validateQuery } = require('../transit/mobile-parser');

async function page(url) {
  const response = await axios.get(url, { timeout: 20000, maxContentLength: 2000000, headers: {
    'User-Agent': 'CTTrains-API/1.1 (https://github.com/Xhanti-mbasa/cttrains-api; unofficial community tool)',
    Accept: 'text/html',
  } });
  return response.data;
}
function createMobileRouter({ readPage = page, cacheStore = cache, currentDate = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Johannesburg', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()) } = {}) {
const router = express.Router();
async function catalog() {
  const cached = await cacheStore.get('mobile:v1:catalog');
  if (cached) return cached;
  const data = parseCatalog(await readPage('https://cttrains.co.za/train-form.php'));
  await cacheStore.set('mobile:v1:catalog', data, 24 * 60 * 60);
  return data;
}
router.get('/catalog', async (_req, res) => {
  try { res.json(await catalog()); }
  catch (_error) { res.status(502).json({ error: 'The CT Trains catalogue could not be refreshed.' }); }
});
router.get('/journeys', async (req, res) => {
  try {
    const query = { from: req.query.from, to: req.query.to, date: req.query.date, time: req.query.time || '06:00' };
    const today = currentDate();
    const error = validateQuery(query, await catalog(), today);
    if (error) return res.status(400).json({ error });
    const key = `mobile:v1:${JSON.stringify(query)}`;
    const existing = await cacheStore.get(key);
    if (existing) return res.json(existing);
    const [year, month, day] = query.date.split('-');
    const url = new URL('https://cttrains.co.za/train-schedule.php');
    url.search = new URLSearchParams({ fromStation: query.from, toStation: query.to, travelDate: `${day}/${month}/${year}`, departureTime: query.time });
    const html = await readPage(url.toString());
    const result = { query, journeys: parseJourneys(html, query.time), fetchedAt: Date.now(), fares: parseFares(html), sourceId: 'cttrains-api' };
    await cacheStore.set(key, result, 60 * 60);
    res.json(result);
  } catch (_error) { res.status(502).json({ error: 'The CT Trains timetable could not be normalized safely. Try the source website.' }); }
});
return router;
}
module.exports = createMobileRouter();
module.exports.createMobileRouter = createMobileRouter;
