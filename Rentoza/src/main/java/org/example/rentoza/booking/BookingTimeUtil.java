package org.example.rentoza.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Utility class for deriving effective pickup and dropoff DateTimes from Booking entities.
 *
 * Purpose:
 * - Enable time-aware availability searches without modifying Booking schema
 * - Derive LocalDateTime from existing LocalDate + time window fields
 * - Support configurable default times via application.properties
 *
 * Design Decision:
 * - NO schema changes to Booking entity
 * - Derive startDateTime from: startDate + pickupTimeWindow/pickupTime
 * - Derive endDateTime from: endDate + default dropoff time
 *
 * Configuration:
 * - app.booking.default-dropoff-time (default: 18:00)
 * - app.booking.time-windows.morning (default: 09:00)
 * - app.booking.time-windows.afternoon (default: 14:00)
 * - app.booking.time-windows.evening (default: 18:00)
 */
@Component
public class BookingTimeUtil {

    @Value("${app.booking.default-dropoff-time:18:00}")
    private String defaultDropoffTimeString;

    @Value("${app.booking.time-windows.morning:09:00}")
    private String morningTimeString;

    @Value("${app.booking.time-windows.afternoon:14:00}")
    private String afternoonTimeString;

    @Value("${app.booking.time-windows.evening:18:00}")
    private String eveningTimeString;

    /**
     * Derive effective pickup DateTime from booking.
     *
     * Logic:
     * 1. If pickupTimeWindow is EXACT and pickupTime is set → use exact time
     * 2. Otherwise map time window to configured default:
     *    - MORNING → 09:00 (configurable)
     *    - AFTERNOON → 14:00 (configurable)
     *    - EVENING → 18:00 (configurable)
     *    - null/unknown → default to MORNING (09:00)
     *
     * @param booking Booking entity with startDate and pickupTimeWindow
     * @return LocalDateTime representing effective pickup time
     */
    public LocalDateTime derivePickupDateTime(Booking booking) {
        if (booking == null || booking.getStartDate() == null) {
            throw new IllegalArgumentException("Booking and startDate must not be null");
        }

        // Case 1: EXACT time window with explicit pickupTime
        if ("EXACT".equals(booking.getPickupTimeWindow()) && booking.getPickupTime() != null) {
            return booking.getStartDate().atTime(booking.getPickupTime());
        }

        // Case 2: Map time window to default time
        LocalTime pickupTime = switch (booking.getPickupTimeWindow() != null ? booking.getPickupTimeWindow() : "MORNING") {
            case "MORNING" -> LocalTime.parse(morningTimeString);
            case "AFTERNOON" -> LocalTime.parse(afternoonTimeString);
            case "EVENING" -> LocalTime.parse(eveningTimeString);
            default -> LocalTime.parse(morningTimeString); // Fallback to morning
        };

        return booking.getStartDate().atTime(pickupTime);
    }

    /**
     * Derive effective dropoff DateTime from booking.
     *
     * Logic:
     * - Use endDate + default dropoff time (configurable, default: 18:00)
     *
     * Rationale:
     * - No explicit dropoffTime field in Booking entity
     * - Most rentals end in the evening (6 PM is standard)
     * - Configurable via application.properties if needed
     *
     * @param booking Booking entity with endDate
     * @return LocalDateTime representing effective dropoff time
     */
    public LocalDateTime deriveDropoffDateTime(Booking booking) {
        if (booking == null || booking.getEndDate() == null) {
            throw new IllegalArgumentException("Booking and endDate must not be null");
        }

        LocalTime dropoffTime = LocalTime.parse(defaultDropoffTimeString);
        return booking.getEndDate().atTime(dropoffTime);
    }

    /**
     * Get configured default dropoff time.
     * Useful for validation and display purposes.
     *
     * @return LocalTime representing default dropoff time
     */
    public LocalTime getDefaultDropoffTime() {
        return LocalTime.parse(defaultDropoffTimeString);
    }

    /**
     * Get configured morning time window start.
     *
     * @return LocalTime representing morning window start
     */
    public LocalTime getMorningTime() {
        return LocalTime.parse(morningTimeString);
    }

    /**
     * Get configured afternoon time window start.
     *
     * @return LocalTime representing afternoon window start
     */
    public LocalTime getAfternoonTime() {
        return LocalTime.parse(afternoonTimeString);
    }

    /**
     * Get configured evening time window start.
     *
     * @return LocalTime representing evening window start
     */
    public LocalTime getEveningTime() {
        return LocalTime.parse(eveningTimeString);
    }
}
