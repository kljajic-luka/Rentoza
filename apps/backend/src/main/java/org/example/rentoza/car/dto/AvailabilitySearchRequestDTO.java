package org.example.rentoza.car.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.car.Feature;
import org.example.rentoza.car.TransmissionType;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Request DTO for availability search API.
 *
 * Purpose:
 * - Search for cars available in a specific location and time range
 * - Time-aware search using exact timestamps (ISO-8601 LocalDateTime)
 * - UPGRADED: Now includes geospatial coordinates and filter params for server-side filtering
 * - Supports pagination and sorting
 *
 * Example Request:
 * GET /api/cars/availability-search
 *   ?location=beograd
 *   &startTime=2025-01-15T09:00:00
 *   &endTime=2025-01-17T18:00:00
 *   &latitude=44.8176
 *   &longitude=20.4633
 *   &radiusKm=20
 *   &minPrice=50
 *   &make=BMW
 *   &page=0&size=20
 *
 * Validation Rules:
 * - All date/time fields required
 * - location required and non-blank
 * - endTime must be after startTime
 * - startTime cannot be in the past
 * - Minimum rental duration: 1 hour
 * - Maximum search range: 90 days (configurable)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailabilitySearchRequestDTO {

    // ========== Core Availability Params (Required) ==========

    /**
     * Location string (city or region).
     * Example: "beograd", "novi sad", "niš"
     * Case-insensitive, will be normalized to lowercase.
     */
    private String location;

    /**
     * Rental start timestamp (ISO 8601 format: YYYY-MM-DDTHH:mm:ss).
     * Example: "2025-01-15T09:00:00"
     */
    private LocalDateTime startTime;

    /**
     * Rental end timestamp (ISO 8601 format: YYYY-MM-DDTHH:mm:ss).
     * Example: "2025-01-17T18:00:00"
     */
    private LocalDateTime endTime;

    // ========== Geospatial Params (Optional) ==========

    /**
     * Center point latitude for geospatial search.
     * When provided with longitude, enables spatial index search.
     * Example: 44.8176 (Belgrade)
     */
    private Double latitude;

    /**
     * Center point longitude for geospatial search.
     * When provided with latitude, enables spatial index search.
     * Example: 20.4633 (Belgrade)
     */
    private Double longitude;

    /**
     * Search radius in kilometers.
     * Default: 20km when coordinates are provided.
     * Example: 20
     */
    @Builder.Default
    private Double radiusKm = 20.0;

    // ========== Filter Params (Optional) ==========

    /**
     * Minimum price per day filter.
     * Example: 50
     */
    private Double minPrice;

    /**
     * Maximum price per day filter.
     * Example: 200
     */
    private Double maxPrice;

    /**
     * Car make/brand filter.
     * Example: "BMW", "Mercedes-Benz"
     */
    private String make;

    /**
     * Car model filter.
     * Example: "X5", "E-Class"
     */
    private String model;

    /**
     * Minimum year filter.
     * Example: 2020
     */
    private Integer minYear;

    /**
     * Maximum year filter.
     * Example: 2024
     */
    private Integer maxYear;

    /**
     * Minimum seats filter.
     * Example: 4
     */
    private Integer minSeats;

    /**
     * Transmission type filter.
     * Values: AUTOMATIC, MANUAL
     */
    private TransmissionType transmission;

    /**
     * Vehicle type filter (P2 FIX: sedan, SUV, van, etc.).
     * Matched against brand/model/description text since Car entity has no vehicleType field.
     * Example: "SUV", "sedan", "van"
     */
    private String vehicleType;

    /**
     * Fuel type filter (P3 FIX: accepts from frontend).
     * Values: BENZIN, DIESEL, ELECTRIC, HYBRID, LPG
     */
    private String fuelType;

    /**
     * Required features filter.
     * Comma-separated string parsed into list.
     * Example: "BLUETOOTH,USB,NAVIGATION"
     */
    private List<Feature> features;

    // ========== Pagination Params ==========

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

    // ========== Helper Methods ==========

    /**
     * Get start timestamp (already LocalDateTime, no conversion needed).
     * Kept for backward compatibility with service layer.
     *
     * @return LocalDateTime representing rental start
     */
    public LocalDateTime getStartDateTime() {
        return startTime;
    }

    /**
     * Get end timestamp (already LocalDateTime, no conversion needed).
     * Kept for backward compatibility with service layer.
     *
     * @return LocalDateTime representing rental end
     */
    public LocalDateTime getEndDateTime() {
        return endTime;
    }

    /**
     * Check if geospatial search is enabled (coordinates provided).
     *
     * @return true if both latitude and longitude are present
     */
    public boolean hasGeospatialCoordinates() {
        return latitude != null && longitude != null;
    }

    /**
     * Check if any filters are active.
     *
     * @return true if at least one filter is set
     */
    public boolean hasFilters() {
        return minPrice != null ||
               maxPrice != null ||
               make != null ||
               model != null ||
               minYear != null ||
               maxYear != null ||
               minSeats != null ||
               transmission != null ||
               (vehicleType != null && !vehicleType.isBlank()) ||
               (fuelType != null && !fuelType.isBlank()) ||
               (features != null && !features.isEmpty());
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
        if (startTime == null) {
            throw new IllegalArgumentException("Start time is required");
        }
        if (endTime == null) {
            throw new IllegalArgumentException("End time is required");
        }

        // Rule 3: End must be after start
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException(
                "End time must be after start time. " +
                "Start: " + startTime + ", End: " + endTime
            );
        }

        // Rule 4: Start date cannot be in the past
        LocalDate today = LocalDate.now();
        LocalDate startDate = startTime.toLocalDate();
        if (startDate.isBefore(today)) {
            throw new IllegalArgumentException(
                "Start date cannot be in the past. Provided: " + startDate + ", Today: " + today
            );
        }

        // Rule 5: Minimum rental duration (1 hour)
        long durationHours = Duration.between(startTime, endTime).toHours();
        if (durationHours < 1) {
            throw new IllegalArgumentException(
                "Minimum rental duration is 1 hour. Provided duration: " + durationHours + " hours"
            );
        }

        // Rule 6: Maximum search range (90 days)
        LocalDate endDate = endTime.toLocalDate();
        long daysBetween = ChronoUnit.DAYS.between(startTime.toLocalDate(), endDate);
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
