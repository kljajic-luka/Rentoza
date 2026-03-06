import {
  calculateBillablePeriodsSerbia,
  formatTripDurationSerbia,
  getRoundedTripDaysSerbia,
  getTripDurationHoursSerbia,
  parseSerbiaDateTime,
} from './serbia-time.util';

describe('serbia-time.util', () => {
  it('parses Serbia local datetime without shifting the wall clock hour', () => {
    const parsed = parseSerbiaDateTime('2026-03-10T10:30:00');

    const formatter = new Intl.DateTimeFormat('sr-RS', {
      timeZone: 'Europe/Belgrade',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    });

    expect(formatter.format(parsed)).toContain('10:30');
  });

  it('calculates exact Serbia-zone trip duration hours', () => {
    expect(getTripDurationHoursSerbia('2026-03-10T10:00:00', '2026-03-11T16:00:00')).toBe(30);
  });

  it('rounds billable periods up to full 24-hour blocks', () => {
    expect(calculateBillablePeriodsSerbia('2026-03-10T10:00:00', '2026-03-11T16:00:00')).toBe(2);
    expect(getRoundedTripDaysSerbia('2026-03-10T10:00:00', '2026-03-11T16:00:00')).toBe(2);
  });

  it('formats long trips as day labels', () => {
    expect(formatTripDurationSerbia('2026-03-10T10:00:00', '2026-03-12T10:00:00')).toBe('2 dana');
  });
});