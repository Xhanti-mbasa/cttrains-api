'use strict';
const cheerio = require('cheerio');
const validTime = /^(?:[01]\d|2[0-3]):[0-5]\d$/;

function parseCatalog(html) {
  const $ = cheerio.load(html);
  const stations = [...new Set($('select[name=fromStation] option[value]').toArray().map(el => $(el).attr('value').trim()).filter(Boolean))].sort();
  if (stations.length < 2) throw new Error('Unfamiliar station catalogue.');
  const block = html.match(/const\s+holidays\s*=\s*\[([^\]]*)\]/);
  return { stations, holidays: block ? block[1].match(/\d{4}-\d{2}-\d{2}/g) || [] : [] };
}
function parseJourneys(html, notBefore) {
  const $ = cheerio.load(html);
  const cards = $('.bg-white.rounded-lg.shadow-lg').toArray().filter(el => $(el).find('h2').first().text().trim().startsWith('Option '));
  if (!cards.length) {
    const text = $('body').text();
    if (/no (?:train (?:times|schedules)|trains|(?:matching|available) (?:trains|schedules)|routes|journeys|schedules).*?(?:found|available|match|between)/is.test(text) || /no trains/i.test(text) || /no\s+(?:valid|suitable|matching|available)\s+(?:itinerar|route|journey|train|schedule)/i.test(text)) return [];
    throw new Error('Unfamiliar timetable page.');
  }
  const journeys = cards.map(el => {
    const legs = $(el).find('div.relative.pl-6').toArray().map(block => {
      const b = $(block);
      const line = b.find('span.font-semibold').first().text().trim();
      const train = (b.text().match(/Train number:\s*([A-Za-z0-9-]+)/i) || [])[1];
      const stops = b.find('.station-time').toArray().map(time => {
        const nameElement = $(time).parent().find('.station-name').first();
        const label = nameElement.find('span.font-medium').first();
        const row = $(time).parent();
        const explicit = row.attr('data-platform')?.trim() || (row.find('.platform, .station-platform').first().text().trim().match(/^(?:Platform\s*)?([0-9]{1,2}[A-Za-z]?)$/i) || [])[1];
        const platform = /^[0-9]{1,2}[A-Za-z]?$/.test(explicit || '') ? explicit.toUpperCase() : null;
        const name = label.length ? label.text().trim() : nameElement.clone().find('.platform, .station-platform').remove().end().text().trim();
        return { name, time: $(time).text().trim(), ...(platform ? { platform } : {}) };
      });
      if (!line || !train || stops.length < 2 || stops.some(stop => !stop.name || !validTime.test(stop.time))) throw new Error('Incomplete train leg.');
      return { line, train, stops };
    });
    if (!legs.length || legs.slice(1).some((leg, i) => legs[i].stops.at(-1).name !== leg.stops[0].name)) throw new Error('Incomplete train connection.');
    return { legs };
  }).filter(journey => journey.legs[0].stops[0].time >= notBefore);
  return [...new Map(journeys.map(j => [JSON.stringify(j), j])).values()].sort((a, b) => a.legs[0].stops[0].time.localeCompare(b.legs[0].stops[0].time));
}
function parseFares(html) {
  const $ = cheerio.load(html);
  return $('p').toArray().map(el => $(el).text().trim()).find(text => /^Fares:/i.test(text) && text.length <= 800) || null;
}
function validateQuery(query, catalog, today) {
  const { from, to, date, time } = query;
  if (typeof from !== 'string' || typeof to !== 'string' || !catalog.stations.includes(from) || !catalog.stations.includes(to) || from === to) return 'Choose two different stations from the catalogue.';
  if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return 'Use a valid YYYY-MM-DD date.';
  const parsed = new Date(`${date}T12:00:00Z`);
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== date) return 'Use a valid calendar date.';
  const base = new Date(`${today}T12:00:00Z`);
  const last = new Date(Date.UTC(base.getUTCFullYear(), base.getUTCMonth() + 2, 0)).toISOString().slice(0, 10);
  if (date < today || date > last) return 'Choose today through the end of next month.';
  if (parsed.getUTCDay() === 0 || catalog.holidays.includes(date)) return 'The source does not list Sunday or public-holiday services.';
  if (typeof time !== 'string' || !validTime.test(time) || time < '03:00' || Number(time.slice(3)) % 15 !== 0) return 'Use a time from 03:00 in 15-minute intervals.';
  return null;
}
module.exports = { parseCatalog, parseJourneys, parseFares, validateQuery };
