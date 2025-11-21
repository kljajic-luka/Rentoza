/**
 * Booking utility functions for unified completion logic
 *
 * These utilities ensure frontend and backend consistency for determining
 * booking completion status and review eligibility.
 */

/**
 * Check if a booking is considered completed.
 *
 * A booking is completed if:
 * 1. Status is explicitly set to 'COMPLETED', OR
 * 2. The end date is in the past (regardless of status)
 *
 * This unified check ensures frontend and backend consistency for review eligibility.
 *
 * @param booking - Booking object with status and endDate fields
 * @returns true if the booking is completed
 */
export function isBookingCompleted(booking: { status: string; endDate: string | Date }): boolean {
  if (!booking || !booking.endDate) {
    return false;
  }

  const now = new Date();
  const endDate = new Date(booking.endDate);
  // Treat booking as completed only after the END of the end date (23:59:59.999)
  endDate.setHours(23, 59, 59, 999);

  return booking.status === 'COMPLETED' || endDate < now;
}

/**
 * Check if a user can review a booking.
 *
 * A user can review if:
 * 1. The booking is completed (using unified completion check), AND
 * 2. The user has not already reviewed this booking
 *
 * @param booking - Booking object with status, endDate, and hasReview fields
 * @returns true if the booking can be reviewed
 */
export function canReviewBooking(booking: { status: string; endDate: string | Date; hasReview: boolean }): boolean {
  return isBookingCompleted(booking) && !booking.hasReview;
}

/**
 * Check if an owner can review a renter for a booking.
 *
 * An owner can review if:
 * 1. The booking is completed (using unified completion check), AND
 * 2. The owner has not already reviewed the renter
 *
 * @param booking - Booking object with status, endDate, and hasOwnerReview fields
 * @returns true if the owner can review the renter
 */
export function canOwnerReviewRenter(booking: { status: string; endDate: string | Date; hasOwnerReview?: boolean }): boolean {
  return isBookingCompleted(booking) && !booking.hasOwnerReview;
}
