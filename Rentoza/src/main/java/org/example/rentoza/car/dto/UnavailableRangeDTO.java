package org.example.rentoza.car.dto;

import java.time.LocalDateTime;

/**
 * DTO representing an unavailable time range for a car.
 * Used by the availability endpoint to communicate blocked periods to the frontend calendar.
 * 
 * @param start Start timestamp of the unavailable period (inclusive)
 * @param end End timestamp of the unavailable period (inclusive)
 * @param reason The reason why this period is unavailable
 */
public record UnavailableRangeDTO(
    LocalDateTime start,
    LocalDateTime end,
    UnavailabilityReason reason
) {
    /**
     * Enumeration of reasons why a time range might be unavailable.
     */
    public enum UnavailabilityReason {
        /**
         * The car is booked during this period (active/pending booking).
         */
        BOOKING,
        
        /**
         * The owner has manually blocked these dates.
         */
        BLOCKED_DATE,
        
        /**
         * The gap between two bookings is too small to accommodate a minimum rental.
         * This prevents "hugging" issues where unusable voids are created in the schedule.
         */
        GAP_TOO_SMALL
    }
}

