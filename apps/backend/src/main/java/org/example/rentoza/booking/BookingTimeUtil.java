package org.example.rentoza.booking;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Utility class for accessing booking time information.
 *
 * <h2>Exact Timestamp Architecture</h2>
 * With the migration to exact timestamps (startTime/endTime),
 * this utility now simply delegates to the Booking entity's fields.
 *
 * <p>Previously this class derived times from date + time window fields.
 * It is kept for backward compatibility with any code that depends on it.
 */
@Component
public class BookingTimeUtil {

    /**
     * Get the pickup DateTime from booking.
     *
     * @param booking Booking entity with startTime
     * @return LocalDateTime representing pickup time
     */
    public LocalDateTime derivePickupDateTime(Booking booking) {
        if (booking == null || booking.getStartTime() == null) {
            throw new IllegalArgumentException("Booking and startTime must not be null");
        }
        return booking.getStartTime();
    }

    /**
     * Get the dropoff DateTime from booking.
     *
     * @param booking Booking entity with endTime
     * @return LocalDateTime representing dropoff time
     */
    public LocalDateTime deriveDropoffDateTime(Booking booking) {
        if (booking == null || booking.getEndTime() == null) {
            throw new IllegalArgumentException("Booking and endTime must not be null");
        }
        return booking.getEndTime();
    }

    /**
     * Get default dropoff time (10:00 AM standard return time).
     *
     * @return LocalTime representing default dropoff time
     */
    public LocalTime getDefaultDropoffTime() {
        return LocalTime.of(10, 0);
    }

    /**
     * Get morning time window start (for display/legacy purposes).
     *
     * @return LocalTime representing morning window start
     */
    public LocalTime getMorningTime() {
        return LocalTime.of(9, 0);
    }

    /**
     * Get afternoon time window start (for display/legacy purposes).
     *
     * @return LocalTime representing afternoon window start
     */
    public LocalTime getAfternoonTime() {
        return LocalTime.of(14, 0);
    }

    /**
     * Get evening time window start (for display/legacy purposes).
     *
     * @return LocalTime representing evening window start
     */
    public LocalTime getEveningTime() {
        return LocalTime.of(18, 0);
    }
}
