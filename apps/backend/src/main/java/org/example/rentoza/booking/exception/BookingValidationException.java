package org.example.rentoza.booking.exception;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Exception thrown when booking validation fails.
 * 
 * <p>Provides detailed information about the validation failure including:
 * <ul>
 *   <li>Error code for programmatic handling</li>
 *   <li>User-friendly message</li>
 *   <li>Field that caused the error</li>
 *   <li>Additional context details</li>
 * </ul>
 * 
 * <h2>Error Code Categories</h2>
 * <ul>
 *   <li>BOOKING_* - Booking-level validation errors</li>
 *   <li>TEMPORAL_* - Time-related validation errors</li>
 *   <li>PRICING_* - Price-related validation errors</li>
 *   <li>USER_* - User eligibility errors</li>
 *   <li>CAR_* - Car availability/eligibility errors</li>
 * </ul>
 * 
 * @author Rentoza Platform Team
 * @since Phase 9.0 - Edge Case Hardening
 */
public class BookingValidationException extends RuntimeException {

    private final String errorCode;
    private final String field;
    private final String details;
    private final LocalDateTime timestamp;
    private final Map<String, Object> context;

    /**
     * Creates a validation exception with basic information.
     * 
     * @param errorCode Unique error code for programmatic handling
     * @param message User-friendly error message
     * @param field The field that caused the validation failure
     */
    public BookingValidationException(String errorCode, String message, String field) {
        this(errorCode, message, field, null);
    }

    /**
     * Creates a validation exception with detailed context.
     * 
     * @param errorCode Unique error code for programmatic handling
     * @param message User-friendly error message
     * @param field The field that caused the validation failure
     * @param details Additional technical details
     */
    public BookingValidationException(String errorCode, String message, String field, String details) {
        super(message);
        this.errorCode = errorCode;
        this.field = field;
        this.details = details;
        this.timestamp = LocalDateTime.now();
        this.context = new HashMap<>();
    }

    /**
     * Adds additional context to the exception.
     * 
     * @param key Context key
     * @param value Context value
     * @return This exception for fluent chaining
     */
    public BookingValidationException withContext(String key, Object value) {
        this.context.put(key, value);
        return this;
    }

    // ==================== GETTERS ====================

    public String getErrorCode() {
        return errorCode;
    }

    public String getField() {
        return field;
    }

    public String getDetails() {
        return details;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getContext() {
        return new HashMap<>(context);
    }

    // ==================== FACTORY METHODS ====================

    /**
     * Creates an exception for booking in the past.
     */
    public static BookingValidationException bookingInPast(LocalDateTime requestedTime, LocalDateTime currentTime) {
        return new BookingValidationException(
            "BOOKING_IN_PAST",
            "Cannot create a booking in the past",
            "startTime",
            String.format("Requested: %s, Current: %s", requestedTime, currentTime)
        );
    }

    /**
     * Creates an exception for booking too far in advance.
     */
    public static BookingValidationException bookingTooFarInFuture(LocalDateTime requestedTime, int maxMonths) {
        return new BookingValidationException(
            "BOOKING_TOO_FAR_IN_FUTURE",
            "Cannot book more than " + maxMonths + " months in advance",
            "startTime",
            "Requested start: " + requestedTime
        );
    }

    /**
     * Creates an exception for booking duration too short.
     */
    public static BookingValidationException durationTooShort(long hours, int minimumHours) {
        return new BookingValidationException(
            "BOOKING_DURATION_TOO_SHORT",
            "Minimum booking duration is " + minimumHours + " hour(s)",
            "duration",
            "Requested: " + hours + " hours"
        );
    }

    /**
     * Creates an exception for booking duration too long.
     */
    public static BookingValidationException durationTooLong(long days, int maximumDays) {
        return new BookingValidationException(
            "BOOKING_DURATION_TOO_LONG",
            "Maximum booking duration is " + maximumDays + " days",
            "duration",
            "Requested: " + days + " days"
        );
    }

    /**
     * Creates an exception for DST transition conflicts.
     */
    public static BookingValidationException dstTransitionConflict(LocalDateTime time) {
        return new BookingValidationException(
            "BOOKING_DST_GAP",
            "Booking time falls during a daylight saving time transition. Please choose a different time.",
            "startTime",
            "Affected time: " + time
        );
    }

    /**
     * Creates an exception for invalid leap year date.
     */
    public static BookingValidationException invalidLeapYearDate(int year) {
        return new BookingValidationException(
            "BOOKING_INVALID_LEAP_YEAR",
            "February 29th does not exist in year " + year,
            "date"
        );
    }

    /**
     * Creates an exception for negative or zero price.
     */
    public static BookingValidationException invalidPrice(String field, String reason) {
        return new BookingValidationException(
            "PRICING_INVALID",
            reason,
            field
        );
    }

    /**
     * Creates an exception for price mismatch.
     */
    public static BookingValidationException priceMismatch(String expected, String actual) {
        return new BookingValidationException(
            "PRICING_MISMATCH",
            "Price calculation mismatch - please refresh and try again",
            "totalPrice",
            String.format("Expected: %s, Actual: %s", expected, actual)
        );
    }

    /**
     * Creates an exception for owner trying to rent own car.
     */
    public static BookingValidationException ownerCannotRentOwnCar(Long userId, Long carOwnerId) {
        return new BookingValidationException(
            "USER_IS_CAR_OWNER",
            "You cannot rent your own car",
            "carId",
            String.format("User: %d, Car Owner: %d", userId, carOwnerId)
        );
    }

    /**
     * Creates an exception for unverified user.
     */
    public static BookingValidationException userNotVerified(Long userId) {
        return new BookingValidationException(
            "USER_NOT_VERIFIED",
            "Your account must be verified before making a booking",
            "userId",
            "User ID: " + userId
        );
    }

    /**
     * Creates an exception for car not available.
     */
    public static BookingValidationException carNotAvailable(Long carId) {
        return new BookingValidationException(
            "CAR_NOT_AVAILABLE",
            "This car is not available for the selected dates",
            "carId",
            "Car ID: " + carId
        );
    }

    /**
     * Creates an exception for double booking conflict.
     */
    public static BookingValidationException doubleBookingConflict(Long carId, LocalDateTime start, LocalDateTime end) {
        return new BookingValidationException(
            "DOUBLE_BOOKING_CONFLICT",
            "This car is already booked for the selected time period",
            "carId",
            String.format("Car: %d, Period: %s to %s", carId, start, end)
        );
    }

    /**
     * Creates an exception for user already has overlapping booking.
     */
    public static BookingValidationException userHasOverlappingBooking(Long userId, LocalDateTime start, LocalDateTime end) {
        return new BookingValidationException(
            "USER_OVERLAPPING_BOOKING",
            "You already have a booking during this time period",
            "userId",
            String.format("User: %d, Period: %s to %s", userId, start, end)
        );
    }

    // ==================== TO STRING ====================

    @Override
    public String toString() {
        return String.format(
            "BookingValidationException[code=%s, field=%s, message=%s, details=%s, timestamp=%s]",
            errorCode, field, getMessage(), details, timestamp
        );
    }
}
