package org.example.rentoza.exception;

/**
 * Exception thrown when a user attempts to create a booking that overlaps
 * with an existing active or pending booking they already have.
 * 
 * Business Rule: "One Driver, One Car"
 * A renter cannot physically drive two cars simultaneously. This constraint
 * ensures data integrity and prevents double-booking from the user's perspective.
 * 
 * Overlap Logic:
 * Two date ranges overlap if: (NewStart < ExistingEnd) AND (NewEnd > ExistingStart)
 * 
 * Status Filter (blocking statuses):
 * - PENDING_APPROVAL: Request awaiting host decision
 * - ACTIVE: Confirmed and ongoing trip
 * 
 * Non-blocking statuses (user can rebook these dates):
 * - CANCELLED, DECLINED, COMPLETED, EXPIRED, EXPIRED_SYSTEM
 * 
 * HTTP Status: 409 Conflict
 * 
 * Security:
 * - Message should NOT contain PII (no email, phone, etc.)
 * - Use generic message visible to user
 * - Log detailed info server-side only
 * 
 * @see org.example.rentoza.booking.BookingService#createBooking
 * @see org.example.rentoza.booking.BookingRepository#existsOverlappingUserBooking
 */
public class UserOverlapException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = 
            "Ne možete rezervisati dva vozila u isto vreme. " +
            "Već imate aktivnu ili čekajuću rezervaciju za ovaj period.";

    public UserOverlapException() {
        super(DEFAULT_MESSAGE);
    }

    public UserOverlapException(String message) {
        super(message);
    }

    public UserOverlapException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Factory method for creating exception with date context (for logging).
     * The returned exception still has a user-safe message.
     */
    public static UserOverlapException forDates(java.time.LocalDate startDate, java.time.LocalDate endDate) {
        return new UserOverlapException(
                String.format("You already have a trip booked for %s to %s.", startDate, endDate)
        );
    }
}
