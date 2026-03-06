import { SERBIA_TIMEZONE } from './time-validation.util';

interface SerbiaDateParts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

export function parseSerbiaDateTime(dateStr: string): Date {
  if (!dateStr) {
    return new Date(NaN);
  }

  if (/(?:[zZ]|[+-]\d{2}:?\d{2})$/.test(dateStr)) {
    return new Date(dateStr);
  }

  const match = dateStr.match(
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?$/,
  );
  if (!match) {
    return new Date(dateStr);
  }

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6] ?? '0');
  const millisecond = Number((match[7] ?? '0').padEnd(3, '0'));

  const serbiaLocalAsUtcMillis = Date.UTC(year, month - 1, day, hour, minute, second, millisecond);

  let utcMillis = serbiaLocalAsUtcMillis;
  for (let index = 0; index < 2; index++) {
    const offsetMinutes = getTimeZoneOffsetMinutes(utcMillis, SERBIA_TIMEZONE);
    utcMillis = serbiaLocalAsUtcMillis - offsetMinutes * 60_000;
  }

  return new Date(utcMillis);
}

export function getTripDurationHoursSerbia(startTime: string, endTime: string): number {
  const start = parseSerbiaDateTime(startTime);
  const end = parseSerbiaDateTime(endTime);
  return (end.getTime() - start.getTime()) / (1000 * 60 * 60);
}

export function toSerbiaCalendarDate(value: Date | string): Date {
  const parts = getSerbiaDateParts(value);
  return new Date(parts.year, parts.month - 1, parts.day);
}

export function formatDateSerbiaValue(
  value: Date | string,
  options: Intl.DateTimeFormatOptions = {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  },
): string {
  const date = typeof value === 'string' ? parseSerbiaDateTime(value) : value;
  return new Intl.DateTimeFormat('sr-RS', {
    timeZone: SERBIA_TIMEZONE,
    ...options,
  }).format(date);
}

export function formatTimeSerbiaValue(
  value: Date | string,
  options: Intl.DateTimeFormatOptions = {
    hour: '2-digit',
    minute: '2-digit',
  },
): string {
  const date = typeof value === 'string' ? parseSerbiaDateTime(value) : value;
  return new Intl.DateTimeFormat('sr-RS', {
    timeZone: SERBIA_TIMEZONE,
    ...options,
  }).format(date);
}

export function formatDateTimeSerbiaValue(
  value: Date | string,
  options: Intl.DateTimeFormatOptions = {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  },
): string {
  const date = typeof value === 'string' ? parseSerbiaDateTime(value) : value;
  return new Intl.DateTimeFormat('sr-RS', {
    timeZone: SERBIA_TIMEZONE,
    ...options,
  }).format(date);
}

export function getSerbiaTimeHHmm(value: Date | string): string {
  const parts = getSerbiaDateParts(value);
  return `${String(parts.hour).padStart(2, '0')}:${String(parts.minute).padStart(2, '0')}`;
}

export function getSerbiaDateInputValue(value: Date | string, dayOffset = 0): string {
  const calendarDate = toSerbiaCalendarDate(value);
  if (dayOffset !== 0) {
    calendarDate.setDate(calendarDate.getDate() + dayOffset);
  }

  const year = calendarDate.getFullYear();
  const month = String(calendarDate.getMonth() + 1).padStart(2, '0');
  const day = String(calendarDate.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function calculateBillablePeriodsSerbia(startTime: string, endTime: string): number {
  const hours = getTripDurationHoursSerbia(startTime, endTime);
  return Math.max(1, Math.ceil(hours / 24));
}

export function formatTripDurationSerbia(startTime: string, endTime: string): string {
  const hours = Math.round(getTripDurationHoursSerbia(startTime, endTime));

  if (hours < 24) {
    return `${hours} sat${hours === 1 ? '' : hours < 5 ? 'a' : 'i'}`;
  }

  const days = Math.ceil(hours / 24);
  return `${days} dan${days === 1 ? '' : 'a'}`;
}

export function getRoundedTripDaysSerbia(startTime: string, endTime: string): number {
  return calculateBillablePeriodsSerbia(startTime, endTime);
}

function getSerbiaDateParts(value: Date | string): SerbiaDateParts {
  const date = typeof value === 'string' ? parseSerbiaDateTime(value) : value;
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: SERBIA_TIMEZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
    hourCycle: 'h23',
  }).formatToParts(date);

  const get = (type: Intl.DateTimeFormatPartTypes): number => {
    const rawValue = parts.find((part) => part.type === type)?.value;
    return rawValue ? Number(rawValue) : 0;
  };

  return {
    year: get('year'),
    month: get('month'),
    day: get('day'),
    hour: get('hour'),
    minute: get('minute'),
    second: get('second'),
  };
}

function getTimeZoneOffsetMinutes(timestampMs: number, timeZone: string): number {
  const date = new Date(timestampMs);
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
    hourCycle: 'h23',
  }).formatToParts(date);

  const get = (type: Intl.DateTimeFormatPartTypes): number => {
    const value = parts.find((part) => part.type === type)?.value;
    return value ? Number(value) : 0;
  };

  const asIfUtc = Date.UTC(
    get('year'),
    get('month') - 1,
    get('day'),
    get('hour'),
    get('minute'),
    get('second'),
  );

  return Math.round((asIfUtc - timestampMs) / 60_000);
}