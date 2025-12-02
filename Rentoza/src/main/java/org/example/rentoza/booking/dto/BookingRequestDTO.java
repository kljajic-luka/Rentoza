package org.example.rentoza.booking.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * DTO for creating a new booking request.
 * 
 * <h2>Exact Timestamp Architecture</h2>
 * Uses precise start/end timestamps instead of date + time window.
 * 
 * <h2>Timezone</h2>
 * All times are interpreted as Europe/Belgrade local time.
 * Frontend should send ISO-8601 format without offset: "2025-10-10T10:00:00"
 * 
 * <h2>Time Granularity</h2>
 * Times should be on 30-minute boundaries (e.g., 09:00, 09:30, 10:00).
 * 
 * <h2>Minimum Duration</h2>
 * Booking must be at least 24 hours (overnight rental required).
 */
@Getter
@Setter
public class BookingRequestDTO {
    
    @NotNull(message = "Car ID is required")
    private Long carId;

    /**
     * Exact trip start timestamp.
     * Format: ISO-8601 LocalDateTime (e.g., "2025-10-10T10:00:00")
     * Timezone: Interpreted as Europe/Belgrade
     */
    @NotNull(message = "Start time is required")
    @FutureOrPresent(message = "Start time must be now or in the future")
    private LocalDateTime startTime;

    /**
     * Exact trip end timestamp.
     * Format: ISO-8601 LocalDateTime (e.g., "2025-10-12T10:00:00")
     * Timezone: Interpreted as Europe/Belgrade
     */
    @NotNull(message = "End time is required")
    @Future(message = "End time must be in the future")
    private LocalDateTime endTime;

    private String insuranceType = "BASIC"; // BASIC, STANDARD, PREMIUM
    private boolean prepaidRefuel = false;

    /**
     * Validates that end time is after start time.
     */
    @AssertTrue(message = "End time must be after start time")
    private boolean isEndTimeAfterStartTime() {
        if (startTime == null || endTime == null) {
            return true; // Let @NotNull handle nulls
        }
        return endTime.isAfter(startTime);
    }

    /**
     * Validates minimum rental duration (24 hours).
     */
    @AssertTrue(message = "Minimum rental duration is 24 hours")
    private boolean isMinimumDurationMet() {
        if (startTime == null || endTime == null) {
            return true; // Let @NotNull handle nulls
        }
        long hours = ChronoUnit.HOURS.between(startTime, endTime);
        return hours >= 24;
    }

    /**
     * Validates that times are on 30-minute boundaries.
     */
    @AssertTrue(message = "Times must be on 30-minute boundaries (e.g., 09:00, 09:30)")
    private boolean isTimeGranularityValid() {
        if (startTime == null || endTime == null) {
            return true; // Let @NotNull handle nulls
        }
        boolean startValid = startTime.getMinute() == 0 || startTime.getMinute() == 30;
        boolean endValid = endTime.getMinute() == 0 || endTime.getMinute() == 30;
        return startValid && endValid;
    }

    // ========== CONVENIENCE METHODS ==========

    /**
     * Calculate the duration in hours.
     */
    public long getDurationHours() {
        if (startTime == null || endTime == null) {
            return 0;
        }
        return ChronoUnit.HOURS.between(startTime, endTime);
    }

    /**
     * Calculate the duration in 24-hour periods (for pricing).
     * Rounds up to the nearest full day.
     */
    public int getDurationDays() {
        long hours = getDurationHours();
        return Math.max(1, (int) Math.ceil(hours / 24.0));
    }
}
