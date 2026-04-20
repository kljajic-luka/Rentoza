package org.example.rentoza.booking.exception;

/**
 * Exception thrown when a booking conflict is detected (double booking).
 * 
 * <p>This typically occurs when:
 * <ul>
 *   <li>Two users try to book the same car for overlapping times</li>
 *   <li>A user tries to book a car that's already reserved</li>
 *   <li>Race condition during booking creation</li>
 * </ul>
 * 
 * @author Rentoza Platform Team
 * @since Phase 9.0 - Edge Case Hardening
 */
public class BookingConflictException extends RuntimeException {

    private final Long carId;
    private final Long existingBookingId;

    public BookingConflictException(String message) {
        super(message);
        this.carId = null;
        this.existingBookingId = null;
    }

    public BookingConflictException(String message, Long carId) {
        super(message);
        this.carId = carId;
        this.existingBookingId = null;
    }

    public BookingConflictException(String message, Long carId, Long existingBookingId) {
        super(message);
        this.carId = carId;
        this.existingBookingId = existingBookingId;
    }

    public Long getCarId() {
        return carId;
    }

    public Long getExistingBookingId() {
        return existingBookingId;
    }
}
