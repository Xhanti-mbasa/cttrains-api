/**
 * South African public holiday checker.
 * Returns true if a given date is a public holiday or Sunday (no train service).
 *
 * Fixed holidays are listed by MM-DD.
 * Easter-based holidays are computed dynamically.
 */

const FIXED_HOLIDAYS = new Set([
  '01-01', // New Year's Day
  '03-21', // Human Rights Day
  '04-27', // Freedom Day
  '05-01', // Workers' Day
  '06-16', // Youth Day
  '08-09', // National Women's Day
  '09-24', // Heritage Day
  '12-16', // Day of Reconciliation
  '12-25', // Christmas Day
  '12-26', // Day of Goodwill
]);

/**
 * Compute Easter Sunday for a given year (Anonymous Gregorian algorithm).
 * @param {number} year
 * @returns {Date}
 */
function easterSunday(year) {
  const a = year % 19;
  const b = Math.floor(year / 100);
  const c = year % 100;
  const d = Math.floor(b / 4);
  const e = b % 4;
  const f = Math.floor((b + 8) / 25);
  const g = Math.floor((b - f + 1) / 3);
  const h = (19 * a + b - d - g + 15) % 30;
  const i = Math.floor(c / 4);
  const k = c % 4;
  const l = (32 + 2 * e + 2 * i - h - k) % 7;
  const m = Math.floor((a + 11 * h + 22 * l) / 451);
  const month = Math.floor((h + l - 7 * m + 114) / 31);
  const day = ((h + l - 7 * m + 114) % 31) + 1;
  return new Date(year, month - 1, day);
}

/**
 * Returns the set of Easter-based holiday dates for a given year.
 * @param {number} year
 * @returns {Set<string>}  Set of 'YYYY-MM-DD' strings
 */
function easterHolidays(year) {
  const easter = easterSunday(year);
  const addDays = (date, n) => {
    const d = new Date(date);
    d.setDate(d.getDate() + n);
    return d;
  };
  const fmt = (d) => d.toISOString().slice(0, 10);

  return new Set([
    fmt(addDays(easter, -2)), // Good Friday
    fmt(addDays(easter, 1)),  // Family Day (Easter Monday)
  ]);
}

/**
 * Checks if train service is expected to be suspended on a date.
 * Trains don't run on Sundays or public holidays (unless special events).
 *
 * @param {Date|string} date  Date object or 'YYYY-MM-DD' string
 * @returns {{ suspended: boolean, reason: string|null }}
 */
function checkServiceSuspension(date) {
  const d = typeof date === 'string' ? new Date(date) : date;
  const dayOfWeek = d.getDay(); // 0 = Sunday

  if (dayOfWeek === 0) {
    return { suspended: true, reason: 'No service on Sundays' };
  }

  const mmdd = d.toISOString().slice(5, 10);
  if (FIXED_HOLIDAYS.has(mmdd)) {
    return { suspended: true, reason: 'Public holiday — no scheduled service' };
  }

  const year = d.getFullYear();
  const easter = easterHolidays(year);
  const ymd = d.toISOString().slice(0, 10);
  if (easter.has(ymd)) {
    return { suspended: true, reason: 'Public holiday (Easter) — no scheduled service' };
  }

  return { suspended: false, reason: null };
}

module.exports = { checkServiceSuspension, easterSunday };
