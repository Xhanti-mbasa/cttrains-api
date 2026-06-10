/**
 * Station and line data for the CTTrains API.
 * Sourced from cttrains.co.za station selectors and route pages.
 */

const LINES = {
  southern: {
    id: 'southern',
    name: 'Southern Line',
    description: 'Travels from central Cape Town through the Southern Suburbs to Muizenberg, then along the edge of False Bay to Simon\'s Town.',
    terminus_a: 'Cape Town',
    terminus_b: 'Simonstown',
    operates_weekdays: true,
    operates_saturdays: true,
    operates_sundays: false,
    saturday_note: 'Saturday: trains run Cape Town to Fish Hoek only. Shuttle service operates between Fish Hoek and Simonstown.',
    weekday_note: 'From 2 Feb 2026, weekday trains run through to Simonstown.',
    route_url: 'https://cttrains.co.za/ss_route_select.php',
    stations: [
      'Cape Town', 'Woodstock', 'Salt River', 'Observatory', 'Mowbray',
      'Rosebank', 'Rondebosch', 'Newlands', 'Claremont', 'Harfield Road',
      'Kenilworth', 'Wynberg', 'Wittebome', 'Plumstead', 'Steurhof',
      'Dieprivier', 'Heathfield', 'Retreat', 'Steenberg', 'Lakeside',
      'False Bay', 'Muizenberg', 'St James', 'Kalk Bay', 'Fish Hoek',
      'Sunny Cove', 'Glencairn', 'Simonstown',
    ],
  },

  cape_flats: {
    id: 'cape_flats',
    name: 'Cape Flats Line',
    description: 'Connects central Cape Town with the suburbs of Pinelands, Athlone, Lansdowne, Ottery and Retreat.',
    terminus_a: 'Cape Town',
    terminus_b: 'Retreat',
    operates_weekdays: true,
    operates_saturdays: true,
    operates_sundays: false,
    route_url: 'https://cttrains.co.za/new/cf_route_select.php',
    stations: [
      'Cape Town', 'Woodstock', 'Salt River', 'Koeberg Road', 'Maitland',
      'Ndabeni', 'Pinelands', 'Langa', 'Bonteheuwel', 'Heideveld',
      'Nyanga', 'Philippi', 'Athlone', 'Hazendal', 'Netreg',
      'Lavistown', 'Lansdowne', 'Crawford', 'Ottery', 'Southfield',
      'Heathfield', 'Retreat',
    ],
  },

  central: {
    id: 'central',
    name: 'Central Line',
    description: 'Serves Khayelitsha and Mitchell\'s Plain as well as a route to Bellville via Sarepta.',
    terminus_a: 'Cape Town',
    terminus_b: 'Khayelitsha / Bellville',
    operates_weekdays: true,
    operates_saturdays: true,
    operates_sundays: false,
    route_url: 'https://cttrains.co.za/cl_route_select.php',
    branches: [
      { name: 'Khayelitsha', terminus: 'Chris Hani' },
      { name: 'Mitchell\'s Plain / Kapteinsklip', terminus: 'Kapteinsklip' },
      { name: 'Bellville via Sarepta', terminus: 'Bellville' },
    ],
    stations: [
      'Cape Town', 'Woodstock', 'Salt River', 'Koeberg Road', 'Maitland',
      'Ndabeni', 'Mutual', 'Esplanade', 'Pinelands', 'Langa',
      'Bonteheuwel', 'Netreg', 'Heideveld', 'Lavistown', 'Nyanga',
      'Philippi', 'Belhar', 'Unibell', 'Pentech', 'Lentegeur',
      'Stock Road', 'Sarepta', 'Mitchells Plain', 'Mandalay', 'Bellville',
      'Kapteinsklip', 'Nolungile', 'Nonkqubela', 'Khayelitsha',
      'Kuyasa', 'Chris Hani',
    ],
  },

  northern: {
    id: 'northern',
    name: 'Northern Line',
    description: 'Covers the Northern Suburbs of Cape Town and extends to Stellenbosch, Wellington and Strand. Includes Monte Vista line trains that continue past Bellville.',
    terminus_a: 'Cape Town',
    terminus_b: 'Wellington / Muldersvlei / Strand',
    operates_weekdays: true,
    operates_saturdays: true,
    operates_sundays: false,
    route_url: 'https://cttrains.co.za/nl_route_select.php',
    branches: [
      { name: 'Wellington', terminus: 'Wellington' },
      { name: 'Muldersvlei via Stellenbosch', terminus: 'Muldersvlei' },
      { name: 'Strand via Somerset West', terminus: 'Strand' },
    ],
    stations: [
      'Cape Town', 'Woodstock', 'Salt River', 'Koeberg Road', 'Maitland',
      'Ndabeni', 'Mutual', 'Thornton', 'Goodwood', 'Vasco',
      'Monte Vista', 'De Grendel', 'Century City', 'Kentemade', 'Elsies River',
      'Tygerberg', 'Parow', 'Akasia Park', 'Avondale', 'Bellville',
      'Stikland', 'Kuils River', 'Blackheath', 'Brackenfell', 'Kraaifontein',
      'Eerste River', 'Eikenfontein', 'Faure', 'Firgrove', 'Somerset West',
      'Strand',
      'Lynedoch', 'Van Der Stel', 'Vlottenburg', 'Stellenbosch',
      'Dal Josafat', 'Mbekweni', 'Paarl', 'Huguenot', 'Wellington',
      'Muldersvlei',
      'Oosterzee', 'Meltonrose', 'Du Toit',
    ],
  },

  monte_vista: {
    id: 'monte_vista',
    name: 'Monte Vista to Bellville',
    description: 'Monte Vista line trains passing Century City, Monte Vista and N1 City, finishing at Bellville station.',
    terminus_a: 'Cape Town',
    terminus_b: 'Bellville',
    operates_weekdays: true,
    operates_saturdays: true,
    operates_sundays: false,
    route_url: 'https://cttrains.co.za/mv_route_select.php',
    stations: [
      'Cape Town', 'Woodstock', 'Salt River', 'Koeberg Road', 'Maitland',
      'Century City', 'Monte Vista', 'Kentemade', 'Elsies River',
      'Tygerberg', 'Parow', 'Akasia Park', 'Avondale', 'Bellville',
    ],
  },
};

// Master station list — all unique stations across all lines
const ALL_STATIONS = [
  'Akasia Park', 'Athlone', 'Avondale', 'Belhar', 'Bellville', 'Blackheath',
  'Bonteheuwel', 'Brackenfell', 'Cape Town', 'Century City', 'Chris Hani',
  'Claremont', 'Crawford', 'Dal Josafat', 'De Grendel', 'Dieprivier',
  'Du Toit', 'Eerste River', 'Eikenfontein', 'Elsies River', 'Esplanade',
  'False Bay', 'Faure', 'Firgrove', 'Fish Hoek', 'Glencairn', 'Goodwood',
  'Harfield Road', 'Hazendal', 'Heathfield', 'Heideveld', 'Huguenot',
  'Kalk Bay', 'Kapteinsklip', 'Kenilworth', 'Kentemade', 'Khayelitsha',
  'Klapmuts', 'Koeberg Road', 'Kraaifontein', 'Kuils River', 'Kuyasa',
  'Lakeside', 'Langa', 'Lansdowne', 'Lavistown', 'Lentegeur', 'Lynedoch',
  'Maitland', 'Mandalay', 'Mbekweni', 'Meltonrose', 'Mitchells Plain',
  'Monte Vista', 'Mowbray', 'Muizenberg', 'Muldersvlei', 'Mutual',
  'Ndabeni', 'Netreg', 'Newlands', 'Nolungile', 'Nonkqubela', 'Nyanga',
  'Observatory', 'Oosterzee', 'Ottery', 'Paarl', 'Parow', 'Pentech',
  'Philippi', 'Pinelands', 'Plumstead', 'Retreat', 'Rondebosch', 'Rosebank',
  'Salt River', 'Sarepta', 'Simonstown', 'Somerset West', 'Southfield',
  'St James', 'Steenberg', 'Stellenbosch', 'Steurhof', 'Stikland',
  'Stock Road', 'Strand', 'Sunny Cove', 'Thornton', 'Tygerberg', 'Unibell',
  'Van Der Stel', 'Vasco', 'Vlottenburg', 'Wellington', 'Wetton',
  'Wittebome', 'Woltemade', 'Woodstock', 'Wynberg', 'Ysterplaat',
];

/**
 * Returns which lines serve a given station name (case-insensitive).
 */
function getStationLines(stationName) {
  const normalized = stationName.toLowerCase().trim();
  return Object.values(LINES)
    .filter(line => line.stations.some(s => s.toLowerCase() === normalized))
    .map(line => line.id);
}

/**
 * Returns a station object with its line memberships.
 */
function buildStationObject(name) {
  return {
    id: name.toLowerCase().replace(/\s+/g, '_').replace(/'/g, ''),
    name,
    lines: getStationLines(name),
  };
}

module.exports = { LINES, ALL_STATIONS, getStationLines, buildStationObject };
