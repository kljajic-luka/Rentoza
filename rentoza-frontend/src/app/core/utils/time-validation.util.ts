/**
 * Time Window Validation Utilities
 *
 * PHASE 2: Validation Alignment
 *
 * Centralized validation logic for booking time windows.
 * These constants and functions must match backend validation (BookingService.java).
 *
 * @see CarBookingSettings.java (backend)
 * @see BookingService.createBooking() (backend validation)
 */

// ============================================================================
// SYSTEM-WIDE DEFAULTS (Match CarBookingSettings.java)
// ============================================================================

/**
 * Default minimum trip duration in hours.
 * Cars can override this via bookingSettings.minTripHours
 */
export const DEFAULT_MIN_TRIP_HOURS = 24;

/**
 * Default maximum trip duration in days.
 * Cars can override this via bookingSettings.maxTripDays
 */
export const DEFAULT_MAX_TRIP_DAYS = 30;

/**
 * Default advance notice required in hours.
 * Cars can override this via bookingSettings.advanceNoticeHours
 */
export const DEFAULT_ADVANCE_NOTICE_HOURS = 1;

/**
 * Default buffer between trips in hours.
 * Cars can override this via bookingSettings.prepBufferHours
 */
export const DEFAULT_PREP_BUFFER_HOURS = 3;

/**
 * Time granularity for booking times (30-minute intervals).
 */
export const TIME_GRANULARITY_MINUTES = 30;

// ============================================================================
// SERBIA TIMEZONE CONFIGURATION
// ============================================================================

/**
 * Serbia timezone identifier.
 * All booking times are displayed and validated in Serbia local time.
 */
export const SERBIA_TIMEZONE = 'Europe/Belgrade';

// ============================================================================
// VALIDATION FUNCTIONS
// ============================================================================

export interface TimeValidationResult {
  valid: boolean;
  errorMessage?: string;
}

/**
 * Validate that trip start is at least `advanceNoticeHours` from now.
 *
 * @param startTime Trip start time
 * @param advanceNoticeHours Required advance notice (default: 1)
 * @returns Validation result with Serbian error message if invalid
 */
export function validateLeadTime(
  startTime: Date,
  advanceNoticeHours: number = DEFAULT_ADVANCE_NOTICE_HOURS
): TimeValidationResult {
  const now = new Date();
  const minStartTime = new Date(now.getTime() + advanceNoticeHours * 60 * 60 * 1000);

  if (startTime < minStartTime) {
    const formattedMinStart = formatDateTimeSerbia(minStartTime);
    return {
      valid: false,
      errorMessage:
        advanceNoticeHours === 1
          ? `Rezervacija mora početi najranije za 1 sat (${formattedMinStart})`
          : `Rezervacija mora početi najranije za ${advanceNoticeHours} sata (${formattedMinStart})`,
    };
  }

  return { valid: true };
}

/**
 * Validate that trip duration meets minimum requirement.
 *
 * @param startTime Trip start time
 * @param endTime Trip end time
 * @param minTripHours Required minimum hours (default: 24)
 * @returns Validation result with Serbian error message if invalid
 */
export function validateMinimumDuration(
  startTime: Date,
  endTime: Date,
  minTripHours: number = DEFAULT_MIN_TRIP_HOURS
): TimeValidationResult {
  const durationMs = endTime.getTime() - startTime.getTime();
  const durationHours = durationMs / (1000 * 60 * 60);

  if (durationHours < minTripHours) {
    return {
      valid: false,
      errorMessage: `Minimalno trajanje iznajmljivanja je ${minTripHours} sati. Vaše trajanje: ${Math.floor(
        durationHours
      )} sati.`,
    };
  }

  return { valid: true };
}

/**
 * Validate that trip duration doesn't exceed maximum.
 *
 * @param startTime Trip start time
 * @param endTime Trip end time
 * @param maxTripDays Maximum allowed days (default: 30)
 * @returns Validation result with Serbian error message if invalid
 */
export function validateMaximumDuration(
  startTime: Date,
  endTime: Date,
  maxTripDays: number = DEFAULT_MAX_TRIP_DAYS
): TimeValidationResult {
  const durationMs = endTime.getTime() - startTime.getTime();
  const durationDays = durationMs / (1000 * 60 * 60 * 24);

  if (durationDays > maxTripDays) {
    return {
      valid: false,
      errorMessage: `Maksimalno trajanje iznajmljivanja je ${maxTripDays} dana. Vaše trajanje: ${Math.ceil(
        durationDays
      )} dana.`,
    };
  }

  return { valid: true };
}

/**
 * Validate that times are on 30-minute boundaries.
 *
 * @param time Time to validate
 * @returns Validation result with Serbian error message if invalid
 */
export function validateTimeGranularity(time: Date): TimeValidationResult {
  const minutes = time.getMinutes();

  if (minutes !== 0 && minutes !== 30) {
    return {
      valid: false,
      errorMessage: 'Vreme mora biti na punom satu ili pola sata (npr. 10:00 ili 10:30)',
    };
  }

  return { valid: true };
}

/**
 * Validate that end time is after start time.
 *
 * @param startTime Trip start time
 * @param endTime Trip end time
 * @returns Validation result with Serbian error message if invalid
 */
export function validateEndAfterStart(startTime: Date, endTime: Date): TimeValidationResult {
  if (endTime <= startTime) {
    return {
      valid: false,
      errorMessage: 'Krajnje vreme mora biti posle početnog vremena',
    };
  }

  return { valid: true };
}

/**
 * Comprehensive validation of booking time window.
 * Runs all validations and returns first error or success.
 *
 * @param startTime Trip start time
 * @param endTime Trip end time
 * @param options Optional car-specific settings override
 * @returns Validation result
 */
export function validateBookingTimeWindow(
  startTime: Date,
  endTime: Date,
  options?: {
    advanceNoticeHours?: number;
    minTripHours?: number;
    maxTripDays?: number;
  }
): TimeValidationResult {
  // 1. End must be after start
  const orderResult = validateEndAfterStart(startTime, endTime);
  if (!orderResult.valid) return orderResult;

  // 2. Lead time check
  const leadTimeResult = validateLeadTime(
    startTime,
    options?.advanceNoticeHours ?? DEFAULT_ADVANCE_NOTICE_HOURS
  );
  if (!leadTimeResult.valid) return leadTimeResult;

  // 3. Minimum duration
  const minDurationResult = validateMinimumDuration(
    startTime,
    endTime,
    options?.minTripHours ?? DEFAULT_MIN_TRIP_HOURS
  );
  if (!minDurationResult.valid) return minDurationResult;

  // 4. Maximum duration
  const maxDurationResult = validateMaximumDuration(
    startTime,
    endTime,
    options?.maxTripDays ?? DEFAULT_MAX_TRIP_DAYS
  );
  if (!maxDurationResult.valid) return maxDurationResult;

  return { valid: true };
}

// ============================================================================
// FORMATTING HELPERS
// ============================================================================

/**
 * Format datetime in Serbia timezone with timezone indicator.
 *
 * @param date Date to format
 * @returns Formatted string like "02.01.2026 15:30 CET"
 */
export function formatDateTimeSerbia(date: Date): string {
  return date.toLocaleString('sr-RS', {
    timeZone: SERBIA_TIMEZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    timeZoneName: 'short', // Shows "CET" or "CEST"
  });
}

/**
 * Format date only in Serbia timezone.
 *
 * @param date Date to format
 * @returns Formatted string like "02.01.2026"
 */
export function formatDateSerbia(date: Date): string {
  return date.toLocaleDateString('sr-RS', {
    timeZone: SERBIA_TIMEZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
}

/**
 * Format time only in Serbia timezone.
 *
 * @param date Date to format
 * @returns Formatted string like "15:30"
 */
export function formatTimeSerbia(date: Date): string {
  return date.toLocaleTimeString('sr-RS', {
    timeZone: SERBIA_TIMEZONE,
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * Calculate trip duration in hours.
 *
 * @param startTime Trip start
 * @param endTime Trip end
 * @returns Duration in hours (decimal)
 */
export function getTripDurationHours(startTime: Date, endTime: Date): number {
  return (endTime.getTime() - startTime.getTime()) / (1000 * 60 * 60);
}

/**
 * Calculate trip duration in days.
 *
 * @param startTime Trip start
 * @param endTime Trip end
 * @returns Duration in days (decimal)
 */
export function getTripDurationDays(startTime: Date, endTime: Date): number {
  return getTripDurationHours(startTime, endTime) / 24;
}

/**
 * Get the earliest possible start time (now + advance notice).
 *
 * @param advanceNoticeHours Required advance notice
 * @returns Earliest allowed start Date
 */
export function getEarliestStartTime(
  advanceNoticeHours: number = DEFAULT_ADVANCE_NOTICE_HOURS
): Date {
  return new Date(Date.now() + advanceNoticeHours * 60 * 60 * 1000);
}
