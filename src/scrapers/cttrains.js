const axios = require('axios');
const cheerio = require('cheerio');

const BASE_URL = 'https://cttrains.co.za';

// The site has migrated all schedules to a unified GET endpoint
const SCHEDULE_URL = `${BASE_URL}/train-schedule.php`;
const STATUS_URL = `${BASE_URL}/status2.php`;

// Deprecated URLs kept for backwards compatibility in the API routes
const LINE_URLS = {
  southern:    `${BASE_URL}/ss_route_select.php`,
  cape_flats:  `${BASE_URL}/cf_route_select.php`,
  central:     `${BASE_URL}/cl_route_select.php`,
  northern:    `${BASE_URL}/nl_route_select.php`,
  monte_vista: `${BASE_URL}/mv_route_select.php`,
};

// Hardcoded terminus stations since the line-specific API was removed by the site
const LINE_TERMINUS = {
  southern: { from: 'Cape Town', to: "Simon's Town" },
  cape_flats: { from: 'Cape Town', to: 'Retreat' },
  central: { from: 'Cape Town', to: 'Chris Hani' },
  northern: { from: 'Cape Town', to: 'Bellville' },
  monte_vista: { from: 'Cape Town', to: 'Bellville' },
};

const DEFAULT_HEADERS = {
  'User-Agent': 'CTTrains-API/2.0 (https://github.com/Xhanti-mbasa/cttrains-api; unofficial community tool)',
  'Accept': 'text/html,application/xhtml+xml',
  'Accept-Language': 'en-ZA,en;q=0.9',
  'Referer': BASE_URL,
};

/**
 * Normalises a day-of-week string to a Date for the new date-based search.
 */
function getNextDateForDayType(days = 'weekday') {
  const now = new Date();
  const d = days.toLowerCase();
  if (d.includes('sat')) {
    now.setDate(now.getDate() + ((6 - now.getDay() + 7) % 7 || 7));
  } else if (d.includes('sun')) {
    now.setDate(now.getDate() + ((0 - now.getDay() + 7) % 7 || 7));
  } else {
    if (now.getDay() === 0) now.setDate(now.getDate() + 1);
    else if (now.getDay() === 6) now.setDate(now.getDate() + 2);
  }
  
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/**
 * Scrapes schedule results from the new unified GET endpoint.
 *
 * @param {object} params
 * @param {string} params.from       Departure station name
 * @param {string} params.to         Arrival station name
 * @param {string} params.date       Travel date YYYY-MM-DD
 * @param {string} [params.time]     Departure time HH:MM
 * @returns {Promise<object[]>}
 */
async function scrapeSchedule({ from, to, date, time }) {
  const [year, month, day] = date.split('-');
  const formattedDate = `${day}/${month}/${year}`;
  const departureTime = time || '06:00';

  const params = new URLSearchParams({
    fromStation: from,
    toStation: to,
    travelDate: formattedDate,
    departureTime: departureTime,
  });

  const response = await axios.get(`${SCHEDULE_URL}?${params.toString()}`, {
    headers: DEFAULT_HEADERS,
    timeout: 15000,
  });

  return parseScheduleResults(response.data, { from, to, date });
}

/**
 * Simulates a full line timetable by querying the schedule between its terminus stations.
 * The old PHP line endpoints (ss_timetable.php etc.) were removed from the site.
 */
async function scrapeLineTimetable({ line, from, to, days = 'weekday', searchBy = 'departure', time = '05:00' }) {
  const terminus = LINE_TERMINUS[line] || LINE_TERMINUS.northern;
  const queryFrom = from || terminus.from;
  const queryTo = to || terminus.to;
  const queryDate = getNextDateForDayType(days);

  return scrapeSchedule({
    from: queryFrom,
    to: queryTo,
    date: queryDate,
    time: time || '04:00'
  });
}

/**
 * Parses the new Tailwind CSS card-based HTML into a structured array.
 *
 * @param {string} html
 * @param {object} context
 * @returns {object[]}
 */
function parseScheduleResults(html, context = {}) {
  const $ = cheerio.load(html);
  const results = [];

  // Each route option is in a rounded card
  $('.bg-white.rounded-lg.shadow-lg').each((i, el) => {
    const block = $(el);

    // Train number string e.g. "Train number: 3500"
    const headerText = block.find('.font-normal.text-base').text();
    const trainMatch = headerText.match(/Train number:\s*(\w+)/i);
    let trainNumber = trainMatch ? trainMatch[1].trim() : null;
    
    // Normalise to T####
    if (trainNumber && /^\d+$/.test(trainNumber)) {
      trainNumber = 'T' + trainNumber;
    }

    const service = {
      train_number: trainNumber ? trainNumber.toUpperCase() : null,
      departure_time: null,
      arrival_time: null,
      platform: null,
      stops: [],
    };

    // The times are inside .space-y-3 > .station-time
    const stationsList = block.find('.space-y-3');
    
    // First time is departure
    const depTimeStr = stationsList.find('.station-time').first().text().trim();
    if (/^\d{2}:\d{2}$/.test(depTimeStr)) service.departure_time = depTimeStr;

    // Last time is arrival
    const arrTimeStr = stationsList.find('.station-time').last().text().trim();
    if (/^\d{2}:\d{2}$/.test(arrTimeStr)) service.arrival_time = arrTimeStr;

    // Extract platform from any text in the block
    const fullText = block.text();
    const platformMatch = fullText.match(/(?:Platform|P)\s*(\d{1,2})\b/i);
    if (platformMatch) {
      service.platform = parseInt(platformMatch[1], 10);
    }
    
    // Bonus: extract intermediate stops if available
    stationsList.find('.intermediate-stops .flex').each((j, stopEl) => {
      const sTime = $(stopEl).find('.station-time').text().trim();
      const sName = $(stopEl).find('.station-name').text().trim();
      if (sTime && sName) {
        service.stops.push({ name: sName, time: sTime });
      }
    });

    if (service.departure_time || service.train_number) {
      results.push({ ...service, ...context });
    }
  });

  return results;
}

/**
 * Scrapes current service updates and delays from status page.
 * @returns {Promise<object[]>}
 */
async function scrapeServiceUpdates() {
  const response = await axios.get(STATUS_URL, {
    headers: DEFAULT_HEADERS,
    timeout: 15000,
  });

  return parseServiceUpdates(response.data);
}

/**
 * Parses the service updates HTML page.
 * @param {string} html
 * @returns {object[]}
 */
function parseServiceUpdates(html) {
  const $ = cheerio.load(html);
  const updates = [];

  const textBlocks = [];
  $('p, li, .update, .alert, .delay').each((i, el) => {
    const text = $(el).text().trim();
    if (text.length > 20) textBlocks.push(text);
  });

  textBlocks.forEach((block, idx) => {
    const update = parseUpdateBlock(block, idx);
    if (update) updates.push(update);
  });

  return updates;
}

/**
 * Attempts to extract structured fields from a free-text update block.
 * @param {string} text
 * @param {number} idx
 * @returns {object|null}
 */
function parseUpdateBlock(text, idx) {
  const skipPatterns = [
    /please note/i, /no association/i, /community service/i,
    /protection services/i, /transport information/i,
  ];
  if (skipPatterns.some(p => p.test(text))) return null;

  const update = {
    id: `upd_${Date.now()}_${idx}`,
    raw_message: text,
    type: 'disruption',
    line: null,
    train_number: null,
    delay_min: null,
    delay_max: null,
    published_at: new Date().toISOString(),
  };

  const trainMatch = text.match(/T(\d{4})/i);
  if (trainMatch) update.train_number = `T${trainMatch[1]}`;

  if (/southern/i.test(text)) update.line = 'southern';
  else if (/northern/i.test(text)) update.line = 'northern';
  else if (/central/i.test(text)) update.line = 'central';
  else if (/cape flats/i.test(text)) update.line = 'cape_flats';

  if (/cancel/i.test(text)) update.type = 'cancellation';
  else if (/terminat/i.test(text)) update.type = 'truncation';
  else if (/delay/i.test(text) || /additional travel time/i.test(text)) update.type = 'delay';

  const delayMatch = text.match(/(\d+)\s*[-–to]+\s*(\d+)\s*min/i);
  if (delayMatch) {
    update.delay_min = parseInt(delayMatch[1], 10);
    update.delay_max = parseInt(delayMatch[2], 10);
  }

  return update;
}

module.exports = {
  scrapeSchedule,
  scrapeLineTimetable,
  scrapeServiceUpdates,
  parseScheduleResults,
  parseServiceUpdates,
  LINE_URLS,
};
