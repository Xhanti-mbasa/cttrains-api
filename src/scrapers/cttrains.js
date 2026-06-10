const axios = require('axios');
const cheerio = require('cheerio');

const BASE_URL = 'https://cttrains.co.za';

// Maps line IDs to their route selector PHP page
const LINE_URLS = {
  southern:    `${BASE_URL}/ss_route_select.php`,
  cape_flats:  `${BASE_URL}/cf_route_select.php`,
  central:     `${BASE_URL}/cl_route_select.php`,
  northern:    `${BASE_URL}/nl_route_select.php`,
  monte_vista: `${BASE_URL}/mv_route_select.php`,
};

// Maps line IDs to their form action page (where results are posted to)
const TIMETABLE_URLS = {
  southern:    `${BASE_URL}/ss_timetable.php`,
  cape_flats:  `${BASE_URL}/cf_timetable.php`,
  central:     `${BASE_URL}/cl_timetable.php`,
  northern:    `${BASE_URL}/nl_timetable.php`,
  monte_vista: `${BASE_URL}/mv_timetable.php`,
};

const CROSS_LINE_SEARCH_URL = `${BASE_URL}/train-form.php`;
const CROSS_LINE_RESULTS_URL = `${BASE_URL}/train-results.php`;
const STATUS_URL = `${BASE_URL}/status2.php`;

const DEFAULT_HEADERS = {
  'User-Agent': 'CTTrains-API/1.0 (https://github.com/Xhanti-mbasa/cttrains-api; unofficial community tool)',
  'Accept': 'text/html,application/xhtml+xml',
  'Accept-Language': 'en-ZA,en;q=0.9',
  'Referer': BASE_URL,
};

/**
 * Normalises a day-of-week string to the values cttrains.co.za uses.
 * @param {string} day  'weekday' | 'saturday' | 'monday-friday' | 'mon-fri' etc.
 * @returns {'Mon-Fri'|'Saturday'}
 */
function normaliseDays(day = 'weekday') {
  const d = day.toLowerCase().trim();
  if (d === 'saturday' || d === 'sat') return 'Saturday';
  return 'Mon-Fri';
}

/**
 * Scrapes schedule results from the cross-line station-to-station search.
 *
 * @param {object} params
 * @param {string} params.from       Departure station name (exact, as on site)
 * @param {string} params.to         Arrival station name
 * @param {string} params.date       Travel date YYYY-MM-DD
 * @param {string} [params.time]     Departure time HH:MM (defaults to current time)
 * @returns {Promise<object[]>}
 */
async function scrapeSchedule({ from, to, date, time }) {
  const [year, month, day] = date.split('-');
  const formattedDate = `${day}/${month}/${year}`;
  const departureTime = time || '06:00';

  const params = new URLSearchParams({
    dep_station: from,
    arr_station: to,
    travel_date: formattedDate,
    dep_time: departureTime,
  });

  const response = await axios.post(CROSS_LINE_RESULTS_URL, params.toString(), {
    headers: {
      ...DEFAULT_HEADERS,
      'Content-Type': 'application/x-www-form-urlencoded',
      'Origin': BASE_URL,
    },
    timeout: 15000,
  });

  return parseScheduleResults(response.data, { from, to, date });
}

/**
 * Scrapes the full timetable for a specific line between two stations.
 *
 * @param {object} params
 * @param {string} params.line       Line ID
 * @param {string} params.from       Departure station
 * @param {string} params.to         Arrival station
 * @param {string} [params.days]     'weekday' or 'saturday'
 * @param {string} [params.searchBy] 'departure' | 'arrival' | 'all'
 * @param {string} [params.time]     HH:MM
 * @returns {Promise<object[]>}
 */
async function scrapeLineTimetable({ line, from, to, days = 'weekday', searchBy = 'departure', time = '06:00' }) {
  const timetableUrl = TIMETABLE_URLS[line];
  if (!timetableUrl) throw new Error(`Unknown line: ${line}`);

  const [hour, minute] = (time || '06:00').split(':');
  const dayParam = normaliseDays(days);
  const searchByParam = searchBy === 'arrival' ? 'Arrival'
    : searchBy === 'all' ? 'Show Entire Day'
    : 'Departure';

  const params = new URLSearchParams({
    dep_station: from,
    arr_station: to,
    days: dayParam,
    search_by: searchByParam,
    hour,
    minute: minute || '00',
  });

  const response = await axios.post(timetableUrl, params.toString(), {
    headers: {
      ...DEFAULT_HEADERS,
      'Content-Type': 'application/x-www-form-urlencoded',
      'Origin': BASE_URL,
      'Referer': LINE_URLS[line] || BASE_URL,
    },
    timeout: 15000,
  });

  return parseScheduleResults(response.data, { from, to, line, days: dayParam });
}

/**
 * Parses schedule result HTML into a structured array.
 * The site renders results as an HTML table with train numbers and times.
 *
 * @param {string} html
 * @param {object} context
 * @returns {object[]}
 */
function parseScheduleResults(html, context = {}) {
  const $ = cheerio.load(html);
  const results = [];

  // The timetable is rendered as table rows — each row is one train service.
  // Structure varies slightly per page; we handle the most common patterns.
  $('table tr').each((i, row) => {
    const cells = $(row).find('td');
    if (cells.length < 2) return;

    const firstCell = $(cells[0]).text().trim();
    const secondCell = $(cells[1]).text().trim();

    // Skip header rows
    if (firstCell.toLowerCase().includes('train') || firstCell.toLowerCase().includes('depart')) return;

    // Try to extract train number (format: T####)
    const trainMatch = firstCell.match(/T\d{4}/i);
    if (!trainMatch && !/^\d{2}:\d{2}$/.test(firstCell)) return;

    const service = {
      train_number: trainMatch ? trainMatch[0].toUpperCase() : null,
      departure_time: null,
      arrival_time: null,
      stops: [],
    };

    // Collect all time cells from the row
    const times = [];
    cells.each((j, cell) => {
      const text = $(cell).text().trim();
      if (/^\d{2}:\d{2}$/.test(text)) times.push(text);
    });

    if (times.length >= 1) service.departure_time = times[0];
    if (times.length >= 2) service.arrival_time = times[times.length - 1];

    if (service.departure_time || service.train_number) {
      results.push({ ...service, ...context });
    }
  });

  // Fallback: try to extract any time pattern from the page if table parse yields nothing
  if (results.length === 0) {
    const timePattern = /T(\d{4})[^\d]*(\d{2}:\d{2})[^\d]*(\d{2}:\d{2})/g;
    let match;
    while ((match = timePattern.exec(html)) !== null) {
      results.push({
        train_number: `T${match[1]}`,
        departure_time: match[2],
        arrival_time: match[3],
        ...context,
      });
    }
  }

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

  // Updates appear as paragraphs or list items on the status page
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
  // Skip boilerplate text
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

  // Extract train number
  const trainMatch = text.match(/T(\d{4})/i);
  if (trainMatch) update.train_number = `T${trainMatch[1]}`;

  // Detect line
  if (/southern/i.test(text)) update.line = 'southern';
  else if (/northern/i.test(text)) update.line = 'northern';
  else if (/central/i.test(text)) update.line = 'central';
  else if (/cape flats/i.test(text)) update.line = 'cape_flats';

  // Detect type
  if (/cancel/i.test(text)) update.type = 'cancellation';
  else if (/terminat/i.test(text)) update.type = 'truncation';
  else if (/delay/i.test(text) || /additional travel time/i.test(text)) update.type = 'delay';

  // Extract delay range e.g. "15 - 20 minutes" or "20 - 30 minutes"
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
