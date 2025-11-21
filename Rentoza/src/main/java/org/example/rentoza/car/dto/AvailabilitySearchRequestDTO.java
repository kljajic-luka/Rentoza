package org.example.rentoza.car.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * Request DTO for availability search API.
 *
 * Purpose:
 * - Search for cars available in a specific location and time range
 * - Time-aware search (includes date + time, not just date)
 * - Supports pagination and sorting
 *
 * Example Request:
 * GET /api/cars/availability-search
 *   ?location=beograd
 *   &startDate=2025-01-15&startTime=09:00
 *   &endDate=2025-01-17&endTime=18:00
 *   &page=0&size=20
 *
 * Validation Rules:
 * - All date/time fields required
 * - location required and non-blank
 * - endDateTime must be after startDateTime
 * - startDate cannot be in the past
 * - Minimum rental duration: 1 hour
 * - Maximum search range: 90 days (configurable)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailabilitySearchRequestDTO {

    /**
     * Location string (city or region).
     * Example: "beograd", "novi sad", "niš"
     * Case-insensitive, will be normalized to lowercase.
     */
    private String location;

    /**
     * Rental start date (ISO 8601 format: YYYY-MM-DD).
     * Example: "2025-01-15"
     */
    private LocalDate startDate;

    /**
     * Rental start time (ISO 8601 format: HH:mm).
     * Example: "09:00"
     */
    private LocalTime startTime;

    /**
     * Rental end date (ISO 8601 format: YYYY-MM-DD).
     * Example: "2025-01-17"
     */
    private LocalDate endDate;

    /**
     * Rental end time (ISO 8601 format: HH:mm).
     * Example: "18:00"
     */
    private LocalTime endTime;

    /**
     * Page number for pagination (0-indexed).
     * Default: 0
     */
    @Builder.Default
    private Integer page = 0;

    /**
     * Page size for pagination.
     * Default: 20, Max: 100
     */
    @Builder.Default
    private Integer size = 20;

    /**
     * Sort order (optional).
     * Format: "field,direction"
     * Example: "pricePerDay,asc"
     */
    private String sort;

    /**
     * Compute effective start DateTime by combining startDate and startTime.
     *
     * @return LocalDateTime representing rental start
     */
    public LocalDateTime getStartDateTime() {
        if (startDate == null || startTime == null) {
            throw new IllegalStateException("startDate and startTime must be set before calling getStartDateTime()");
        }
        return startDate.atTime(startTime);
    }

    /**
     * Compute effective end DateTime by combining endDate and endTime.
     *
     * @return LocalDateTime representing rental end
     */
    public LocalDateTime getEndDateTime() {
        if (endDate == null || endTime == null) {
            throw new IllegalStateException("endDate and endTime must be set before calling getEndDateTime()");
        }
        return endDate.atTime(endTime);
    }

    /**
     * Validate request parameters.
     *
     * Validation Rules:
     * 1. Location required and non-blank
     * 2. All date/time fields required
     * 3. End DateTime must be after start DateTime
     * 4. Start date cannot be in the past (today is allowed)
     * 5. Minimum rental duration: 1 hour
     * 6. Maximum search range: 90 days
     * 7. Page and size must be non-negative
     * 8. Size must not exceed 100
     *
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        // Rule 1: Location required
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Location is required and cannot be blank");
        }

        // Rule 2: Date/time fields required
        if (startDate == null) {
            throw new IllegalArgumentException("Start date is required");
        }
        if (startTime == null) {
            throw new IllegalArgumentException("Start time is required");
        }
        if (endDate == null) {
            throw new IllegalArgumentException("End date is required");
        }
        if (endTime == null) {
            throw new IllegalArgumentException("End time is required");
        }

        // Get computed DateTimes for further validation
        LocalDateTime startDateTime = getStartDateTime();
        LocalDateTime endDateTime = getEndDateTime();

        // Rule 3: End must be after start
        if (!endDateTime.isAfter(startDateTime)) {
            throw new IllegalArgumentException(
                "End date/time must be after start date/time. " +
                "Start: " + startDateTime + ", End: " + endDateTime
            );
        }

        // Rule 4: Start date cannot be in the past
        LocalDate today = LocalDate.now();
        if (startDate.isBefore(today)) {
            throw new IllegalArgumentException(
                "Start date cannot be in the past. Provided: " + startDate + ", Today: " + today
            );
        }

        // Rule 5: Minimum rental duration (1 hour)
        long durationHours = Duration.between(startDateTime, endDateTime).toHours();
        if (durationHours < 1) {
            throw new IllegalArgumentException(
                "Minimum rental duration is 1 hour. Provided duration: " + durationHours + " hours"
            );
        }

        // Rule 6: Maximum search range (90 days)
        long daysBetween = ChronoUnit.DAYS.between(startDate, endDate);
        if (daysBetween > 90) {
            throw new IllegalArgumentException(
                "Maximum search range is 90 days. Provided range: " + daysBetween + " days"
            );
        }

        // Rule 7: Pagination parameters
        if (page == null || page < 0) {
            throw new IllegalArgumentException("Page number must be non-negative");
        }
        if (size == null || size <= 0) {
            throw new IllegalArgumentException("Page size must be positive");
        }

        // Rule 8: Max page size
        if (size > 100) {
            throw new IllegalArgumentException("Maximum page size is 100. Provided: " + size);
        }
    }

    /**
     * Normalize location string to lowercase and trim whitespace.
     * Call this before using location in queries.
     *
     * @return Normalized location string
     */
    public String getNormalizedLocation() {
        return location != null ? location.trim().toLowerCase() : null;
    }
}
