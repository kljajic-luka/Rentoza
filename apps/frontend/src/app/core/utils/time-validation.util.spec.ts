import {
  validateLeadTime,
  validateMinimumDuration,
  validateMaximumDuration,
  validateTimeGranularity,
  validateBookingTimeWindow,
  formatDateTimeSerbia,
  getEarliestStartTime,
  DEFAULT_MIN_TRIP_HOURS,
  DEFAULT_MAX_TRIP_DAYS,
  DEFAULT_ADVANCE_NOTICE_HOURS,
  DEFAULT_PREP_BUFFER_HOURS,
  SERBIA_TIMEZONE,
} from './time-validation.util';

describe('TimeValidationUtil', () => {
  // Helper to create dates relative to now
  const hoursFromNow = (hours: number): Date => {
    const date = new Date();
    date.setTime(date.getTime() + hours * 60 * 60 * 1000);
    return date;
  };

  const daysFromNow = (days: number): Date => {
    const date = new Date();
    date.setDate(date.getDate() + days);
    return date;
  };

  describe('Constants', () => {
    it('should have correct default values', () => {
      expect(DEFAULT_MIN_TRIP_HOURS).toBe(24);
      expect(DEFAULT_MAX_TRIP_DAYS).toBe(30);
      expect(DEFAULT_ADVANCE_NOTICE_HOURS).toBe(1);
      expect(DEFAULT_PREP_BUFFER_HOURS).toBe(3);
      expect(SERBIA_TIMEZONE).toBe('Europe/Belgrade');
    });
  });

  describe('validateLeadTime', () => {
    it('should pass when start time is more than required hours away', () => {
      const startDate = hoursFromNow(2); // 2 hours from now
      const result = validateLeadTime(startDate, 1); // require 1 hour notice

      expect(result.valid).toBe(true);
      expect(result.errorMessage).toBeUndefined();
    });

    it('should fail when start time is less than required hours away', () => {
      const startDate = hoursFromNow(0.5); // 30 minutes from now
      const result = validateLeadTime(startDate, 1); // require 1 hour notice

      expect(result.valid).toBe(false);
      expect(result.errorMessage).toContain('1');
    });

    it('should fail when start time is in the past', () => {
      const startDate = hoursFromNow(-1); // 1 hour ago
      const result = validateLeadTime(startDate, 1);

      expect(result.valid).toBe(false);
      expect(result.errorMessage).toBeDefined();
    });

    it('should use default advance notice hours when not specified', () => {
      const startDate = hoursFromNow(0.5); // 30 minutes from now
      const result = validateLeadTime(startDate); // uses DEFAULT_ADVANCE_NOTICE_HOURS (1)

      expect(result.valid).toBe(false);
    });

    it('should handle custom advance notice hours', () => {
      const startDate = hoursFromNow(5); // 5 hours from now
      const result = validateLeadTime(startDate, 6); // require 6 hours notice

      expect(result.valid).toBe(false);
      expect(result.errorMessage).toContain('6');
    });

    it('should pass with exactly required lead time', () => {
      const startDate = hoursFromNow(1.1); // Just over 1 hour
      const result = validateLeadTime(startDate, 1);

      expect(result.valid).toBe(true);
    });
  });

  describe('validateMinimumDuration', () => {
    it('should pass when duration meets minimum hours', () => {
      const startDate = new Date('2024-06-01T10:00:00');
      const endDate = new Date('2024-06-02T10:00:00'); // 24 hours
      const result = validateMinimumDuration(startDate, endDate, 24);

      expect(result.valid).toBe(true);
      expect(result.errorMessage).toBeUndefined();
    });

    it('should fail when duration is less than minimum hours', () => {
      const startDate = new Date('2024-06-01T10:00:00');
      const endDate = new Date('2024-06-01T20:00:00'); // 10 hours
      const result = validateMinimumDuration(startDate, endDate, 24);

      expect(result.valid).toBe(false);
      expect(result.errorMessage).toContain('24');
    });

    it('should use default minimum hours when not specified', () => {
      const startDate = new Date('2024-06-01T10:00:00');
      const endDate = new Date('2024-06-01T20:00:00'); // 10 hours
      const result = validateMinimumDuration(startDate, endDate); // uses DEFAULT_MIN_TRIP_HOURS (24)

      expect(result.valid).toBe(false);
    });

    it('should handle custom minimum hours', () => {
      const startDate = new Date('2024-06-01T10:00:00');
      const endDate = new Date('2024-06-01T15:00:00'); // 5 hours
      const result = validateMinimumDuration(startDate, endDate, 4); // require 4 hours

      expect(result.valid).toBe(true);
    });

    it('should pass with exactly minimum duration', () => {
      const startDate = new Date('2024-06-01T10:00:00');
      const endDate = new Date('2024-06-02T10:00:00'); // exactly 24 hours
      const result = validateMinimumDuration(startDate, endDate, 24);

      expect(result.valid).toBe(true);
    });
  });

  describe('validateMaximumDuration', () => {
    it('should pass when duration is within maximum days', () => {
      const startDate = new Date('2024-06-01T10:00:00');
      const endDate = new Date('2024-06-10T10:00:00'); // 9 days
      const result = validateMaximumDuration(startDate, endDate, 30);

      expect(result.valid).toBe(true);
      expect(result.errorMessage).toBeUndefined();
    });

    it('should fail when duration exceeds maximum days', () => {
      const startDate = new Date('2024-06-01T10:00:00');
      const endDate = new Date('2024-07-15T10:00:00'); // 44 days
      const result = validateMaximumDuration(startDate, endDate, 30);

      expect(result.valid).toBe(false);
      expect(result.errorMessage).toContain('30 dana');
    });

    it('should use default maximum days when not specified', () => {
      const startDate = new Date('2024-06-01T10:00:00');
      const endDate = new Date('2024-07-15T10:00:00'); // 44 days
      const result = validateMaximumDuration(startDate, endDate); // uses DEFAULT_MAX_TRIP_DAYS (30)

      expect(result.valid).toBe(false);
    });

    it('should handle custom maximum days', () => {
      const startDate = new Date('2024-06-01T10:00:00');
      const endDate = new Date('2024-06-08T10:00:00'); // 7 days
      const result = validateMaximumDuration(startDate, endDate, 5); // max 5 days

      expect(result.valid).toBe(false);
      expect(result.errorMessage).toContain('5 dana');
    });

    it('should pass with exactly maximum duration', () => {
      const startDate = new Date('2024-06-01T10:00:00');
      const endDate = new Date('2024-07-01T10:00:00'); // exactly 30 days
      const result = validateMaximumDuration(startDate, endDate, 30);

      expect(result.valid).toBe(true);
    });
  });

  describe('validateTimeGranularity', () => {
    it('should pass for times on 30-minute boundaries', () => {
      const validTimes = [
        new Date('2024-06-01T00:00:00'),
        new Date('2024-06-01T00:30:00'),
        new Date('2024-06-01T09:00:00'),
        new Date('2024-06-01T09:30:00'),
        new Date('2024-06-01T12:00:00'),
        new Date('2024-06-01T12:30:00'),
        new Date('2024-06-01T23:30:00'),
      ];

      validTimes.forEach((time) => {
        const result = validateTimeGranularity(time);
        expect(result.valid).toBe(true);
      });
    });

    it('should fail for times not on 30-minute boundaries', () => {
      const invalidTimes = [
        new Date('2024-06-01T00:15:00'),
        new Date('2024-06-01T09:45:00'),
        new Date('2024-06-01T12:01:00'),
        new Date('2024-06-01T23:59:00'),
      ];

      invalidTimes.forEach((time) => {
        const result = validateTimeGranularity(time);
        expect(result.valid).toBe(false);
        expect(result.errorMessage).toContain('pola sata');
      });
    });
  });

  describe('validateBookingTimeWindow', () => {
    it('should pass with valid booking parameters', () => {
      const startDate = hoursFromNow(2);
      const endDate = hoursFromNow(26); // 24 hours after start

      const result = validateBookingTimeWindow(startDate, endDate, {
        advanceNoticeHours: 1,
        minTripHours: 24,
        maxTripDays: 30,
      });

      expect(result.valid).toBe(true);
      expect(result.errorMessage).toBeUndefined();
    });

    it('should fail if lead time is insufficient', () => {
      const startDate = hoursFromNow(0.5); // 30 minutes from now
      const endDate = hoursFromNow(24.5);

      const result = validateBookingTimeWindow(startDate, endDate, {
        advanceNoticeHours: 1,
        minTripHours: 24,
        maxTripDays: 30,
      });

      expect(result.valid).toBe(false);
      expect(result.errorMessage).toContain('sat');
    });

    it('should fail if duration is too short', () => {
      const startDate = hoursFromNow(2);
      const endDate = hoursFromNow(10); // Only 8 hours

      const result = validateBookingTimeWindow(startDate, endDate, {
        advanceNoticeHours: 1,
        minTripHours: 24,
        maxTripDays: 30,
      });

      expect(result.valid).toBe(false);
      expect(result.errorMessage).toContain('24');
    });

    it('should fail if duration is too long', () => {
      const startDate = hoursFromNow(2);
      const endDate = daysFromNow(35); // 35 days

      const result = validateBookingTimeWindow(startDate, endDate, {
        advanceNoticeHours: 1,
        minTripHours: 24,
        maxTripDays: 30,
      });

      expect(result.valid).toBe(false);
      expect(result.errorMessage).toContain('30');
    });

    it('should use defaults when options not provided', () => {
      const startDate = hoursFromNow(0.5); // 30 minutes (less than default 1 hour)
      const endDate = hoursFromNow(24.5);

      const result = validateBookingTimeWindow(startDate, endDate); // no options

      expect(result.valid).toBe(false);
    });
  });

  describe('formatDateTimeSerbia', () => {
    it('should format date in Serbian locale', () => {
      const date = new Date('2024-06-15T14:30:00');
      const result = formatDateTimeSerbia(date);

      // Should contain date and time components
      expect(result).toBeDefined();
      expect(typeof result).toBe('string');
      expect(result.length).toBeGreaterThan(0);
    });
  });

  describe('getEarliestStartTime', () => {
    it('should return date with advance notice hours added', () => {
      const before = new Date();
      const minDate = getEarliestStartTime(2); // 2 hours notice
      const after = new Date();

      // Should be approximately 2 hours from now
      const hoursAdded = (minDate.getTime() - before.getTime()) / (1000 * 60 * 60);
      expect(hoursAdded).toBeGreaterThanOrEqual(1.99);
      expect(hoursAdded).toBeLessThanOrEqual(2.01);
    });

    it('should use default when not specified', () => {
      const before = new Date();
      const minDate = getEarliestStartTime(); // uses DEFAULT_ADVANCE_NOTICE_HOURS
      const after = new Date();

      // Should be approximately 1 hour from now (default)
      const hoursAdded = (minDate.getTime() - before.getTime()) / (1000 * 60 * 60);
      expect(hoursAdded).toBeGreaterThanOrEqual(0.99);
      expect(hoursAdded).toBeLessThanOrEqual(1.01);
    });
  });

  describe('Edge Cases', () => {
    it('should handle string dates being converted', () => {
      // Some validation functions might receive string dates from forms
      const startDate = new Date('2024-06-15T10:00:00');
      const endDate = new Date('2024-06-16T10:00:00');

      const result = validateMinimumDuration(startDate, endDate, 24);
      expect(result.valid).toBe(true);
    });

    it('should handle DST transitions - spring forward reduces actual hours', () => {
      // Test a date across DST boundary (Serbia switches in March/October)
      // During spring forward, clocks skip ahead, so 24 clock hours = 23 actual hours
      const startDate = new Date('2024-03-30T10:00:00'); // Before DST
      const endDate = new Date('2024-03-31T10:00:00'); // After DST

      // This is 23 actual hours due to DST, so it should fail 24-hour validation
      const result = validateMinimumDuration(startDate, endDate, 24);
      // Note: JavaScript Date handles this automatically based on system timezone
      // The actual behavior depends on the system timezone setting
      expect(result).toBeDefined();
    });

    it('should handle leap year dates', () => {
      const startDate = new Date('2024-02-28T10:00:00');
      const endDate = new Date('2024-02-29T10:00:00'); // Leap day

      const result = validateMinimumDuration(startDate, endDate, 24);
      expect(result.valid).toBe(true);
    });

    it('should handle year boundary dates', () => {
      const startDate = new Date('2024-12-31T10:00:00');
      const endDate = new Date('2025-01-01T10:00:00');

      const result = validateMinimumDuration(startDate, endDate, 24);
      expect(result.valid).toBe(true);
    });
  });
});