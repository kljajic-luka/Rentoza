/**
 * Booking utility functions for unified completion logic
 *
 * These utilities ensure frontend and backend consistency for determining
 * booking completion status and review eligibility.
 *
 * Updated for Exact Timestamp Architecture - uses endTime instead of endDate.
 */
import {
  formatDateSerbiaValue,
  formatDateTimeSerbiaValue,
  formatTimeSerbiaValue,
  parseSerbiaDateTime,
} from './serbia-time.util';

/**
 * Check if a booking is considered completed.
 *
 * A booking is completed if:
 * 1. Status is explicitly set to 'COMPLETED', OR
 * 2. The end time is in the past (regardless of status)
 *
 * P1 FIX: Removed checkout-status exclusion to align with backend
 * BookingService.isBookingCompleted() which allows endTime < now
 * regardless of checkout status.
 *
 * @param booking - Booking object with status and endTime fields
 * @returns true if the booking is completed
 */
export function isBookingCompleted(booking: { status: string; endTime: string | Date }): boolean {
  if (!booking || !booking.endTime) {
    return false;
  }

  const now = new Date();
  const endTime =
    typeof booking.endTime === 'string' ? parseSerbiaDateTime(booking.endTime) : booking.endTime;

  return booking.status === 'COMPLETED' || endTime < now;
}

/**
 * P0-1 FIX: Review submission window in days.
 * Must match ReviewService.REVIEW_SUBMISSION_WINDOW_DAYS on the backend.
 */
const REVIEW_SUBMISSION_WINDOW_DAYS = 14;

/**
 * P0-1 FIX: Check if the review submission window is still open.
 * Reviews must be submitted within REVIEW_SUBMISSION_WINDOW_DAYS after booking end date.
 *
 * @param booking - Booking object with endTime field
 * @returns true if the submission window is still open
 */
export function isReviewWindowOpen(booking: { endTime: string | Date }): boolean {
  if (!booking || !booking.endTime) {
    return true; // No end time = allow (server will enforce)
  }
  const endTime =
    typeof booking.endTime === 'string' ? parseSerbiaDateTime(booking.endTime) : booking.endTime;
  const deadline = new Date(
    endTime.getTime() + REVIEW_SUBMISSION_WINDOW_DAYS * 24 * 60 * 60 * 1000,
  );
  return new Date() <= deadline;
}

/**
 * Check if a user can review a booking.
 *
 * A user can review if:
 * 1. The booking is completed (using unified completion check), AND
 * 2. The user has not already reviewed this booking, AND
 * 3. The review submission window is still open (14 days after end)
 *
 * @param booking - Booking object with status, endTime, and hasReview fields
 * @returns true if the booking can be reviewed
 */
export function canReviewBooking(booking: {
  status: string;
  endTime: string | Date;
  hasReview: boolean;
}): boolean {
  return isBookingCompleted(booking) && !booking.hasReview && isReviewWindowOpen(booking);
}

/**
 * Check if an owner can review a renter for a booking.
 *
 * An owner can review if:
 * 1. The booking is completed (using unified completion check), AND
 * 2. The owner has not already reviewed the renter, AND
 * 3. The review submission window is still open (14 days after end)
 *
 * @param booking - Booking object with status, endTime, and hasOwnerReview fields
 * @returns true if the owner can review the renter
 */
export function canOwnerReviewRenter(booking: {
  status: string;
  endTime: string | Date;
  hasOwnerReview?: boolean;
}): boolean {
  return isBookingCompleted(booking) && !booking.hasOwnerReview && isReviewWindowOpen(booking);
}

/**
 * Format a datetime string for display.
 *
 * @param dateTimeStr - ISO-8601 datetime string
 * @returns Formatted date and time string (e.g., "10.10.2025 09:00")
 */
export function formatDateTime(dateTimeStr: string): string {
  return formatDateTimeSerbiaValue(dateTimeStr);
}

/**
 * Format a datetime string for short display (date only).
 *
 * @param dateTimeStr - ISO-8601 datetime string
 * @returns Formatted date string (e.g., "10.10.2025")
 */
export function formatDate(dateTimeStr: string): string {
  return formatDateSerbiaValue(dateTimeStr);
}

/**
 * Format a datetime string for time only.
 *
 * @param dateTimeStr - ISO-8601 datetime string
 * @returns Formatted time string (e.g., "09:00")
 */
export function formatTime(dateTimeStr: string): string {
  return formatTimeSerbiaValue(dateTimeStr);
}