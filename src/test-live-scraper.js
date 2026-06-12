/**
 * Live Scraper Validation Test
 *
 * Probes cttrains.co.za with real GET parameters to confirm:
 *  - GET parameter names are accepted (fromStation, toStation, travelDate, departureTime)
 *  - HTML structure (bg-white rounded-lg Tailwind classes) is parseable
 *  - Time extraction works
 *  - Train number extraction works
 *
 * Usage:
 *   node src/test-live-scraper.js
 *
 * Requires a live internet connection.
 */

const axios = require('axios');
const cheerio = require('cheerio');

const BASE_URL = 'https://cttrains.co.za';
const RESULTS_URL = `${BASE_URL}/train-schedule.php`;

const DEFAULT_HEADERS = {
  'User-Agent': 'CTTrains-API/2.0 (https://github.com/Xhanti-mbasa/cttrains-api; unofficial community tool)',
  'Accept': 'text/html,application/xhtml+xml',
  'Accept-Language': 'en-ZA,en;q=0.9',
  'Referer': BASE_URL,
};

// Test query
const TEST_PARAMS = {
  fromStation: 'Bellville',
  toStation: 'Cape Town',
  travelDate: getTodayFormatted(),
  departureTime: '06:00',
};

function getTodayFormatted() {
  const now = new Date();
  const d = String(now.getDate()).padStart(2, '0');
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const y = now.getFullYear();
  return `${d}/${m}/${y}`;
}

async function runTest() {
  console.log('\n=== CTTrains Live Scraper Test ===');
  console.log('Target:', RESULTS_URL);
  console.log('Params:', TEST_PARAMS);
  console.log('');

  let html;
  try {
    const params = new URLSearchParams(TEST_PARAMS);
    const response = await axios.get(`${RESULTS_URL}?${params.toString()}`, {
      headers: DEFAULT_HEADERS,
      timeout: 20000,
    });
    html = response.data;
    console.log(`✅ GET request succeeded (HTTP ${response.status})`);
    console.log(`   Response size: ${html.length} chars`);
  } catch (err) {
    console.error('❌ GET request failed:', err.message);
    if (err.response) {
      console.error(`   HTTP ${err.response.status}: ${err.response.statusText}`);
    }
    process.exit(1);
  }

  // --- HTML parse ---
  const $ = cheerio.load(html);
  const cards = $('.bg-white.rounded-lg.shadow-lg').toArray();
  console.log(`\n📊 Schedule cards found: ${cards.length}`);

  const services = [];
  cards.forEach((card, i) => {
    const block = $(card);

    const headerText = block.find('.font-normal.text-base').text();
    const trainMatch = headerText.match(/Train number:\s*(\w+)/i);
    let trainNumber = trainMatch ? trainMatch[1].trim() : null;
    if (trainNumber && /^\d+$/.test(trainNumber)) trainNumber = 'T' + trainNumber;

    const service = {
      train_number: trainNumber ? trainNumber.toUpperCase() : null,
      departure_time: null,
      arrival_time: null,
      platform: null,
      stops: []
    };

    const stationsList = block.find('.space-y-3');
    const depTimeStr = stationsList.find('.station-time').first().text().trim();
    if (/^\d{2}:\d{2}$/.test(depTimeStr)) service.departure_time = depTimeStr;

    const arrTimeStr = stationsList.find('.station-time').last().text().trim();
    if (/^\d{2}:\d{2}$/.test(arrTimeStr)) service.arrival_time = arrTimeStr;

    const textContent = block.text();
    const platformMatch = textContent.match(/(?:Platform|P)\s*(\d{1,2})\b/i);
    if (platformMatch) service.platform = parseInt(platformMatch[1], 10);
    
    stationsList.find('.intermediate-stops .flex').each((j, stopEl) => {
      const sTime = $(stopEl).find('.station-time').text().trim();
      const sName = $(stopEl).find('.station-name').text().trim();
      if (sTime && sName) service.stops.push({ name: sName, time: sTime });
    });

    if (service.departure_time || service.train_number) services.push(service);
  });

  if (services.length > 0) {
    console.log(`✅ HTML parse: ${services.length} service(s) successfully extracted`);
    console.log('   Sample result:', JSON.stringify(services[0], null, 4));
    const withPlatform = services.filter(s => s.platform !== null);
    console.log(`   Platform data: ${withPlatform.length}/${services.length} entries have platform`);
    const withStops = services.filter(s => s.stops.length > 0);
    console.log(`   Intermediate stops: ${withStops.length}/${services.length} entries have intermediate stops extracted`);
  } else {
    console.log('❌ HTML parse: no services found — site structure may have changed again');
  }

  console.log('\n=== Test Complete ===\n');
}

runTest();
