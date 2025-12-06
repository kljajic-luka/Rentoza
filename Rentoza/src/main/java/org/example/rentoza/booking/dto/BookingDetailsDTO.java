package org.example.rentoza.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.booking.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for detailed booking information.
 * 
 * <h2>Exact Timestamp Architecture</h2>
 * Returns precise start/end timestamps for booking display.
 * Times are in Europe/Belgrade timezone.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingDetailsDTO {
    // Trip
    private Long id;
    private BookingStatus status;
    
    /**
     * Exact trip start timestamp.
     * Format: ISO-8601 LocalDateTime (e.g., "2025-10-10T10:00:00")
     */
    private LocalDateTime startTime;
    
    /**
     * Exact trip end timestamp.
     * Format: ISO-8601 LocalDateTime (e.g., "2025-10-12T10:00:00")
     */
    private LocalDateTime endTime;
    
    private BigDecimal totalPrice;
    private String insuranceType;
    private boolean prepaidRefuel;
    private String cancellationPolicy;

    // Car
    private Long carId;
    private String brand;
    private String model;
    private Integer year;
    private String licensePlate; // Only returned to approved renters
    private String location;
    private String primaryImageUrl;
    
    // Car Details
    private Integer seats;
    private String fuelType;
    private Double fuelConsumption;
    private String transmissionType;
    private Integer minRentalDays;
    private Integer maxRentalDays;

    // Host
    private Long hostId;
    private String hostName;
    private Double hostRating;
    private Integer hostTotalTrips;
    private String hostJoinedDate;
    private String hostAvatarUrl;

    // ==================== PICKUP LOCATION (Phase 2.4: Geospatial Migration) ====================

    /**
     * Pickup location latitude (agreed at booking creation).
     * Falls back to car's home location for legacy bookings.
     */
    private Double pickupLatitude;

    /**
     * Pickup location longitude (agreed at booking creation).
     * Falls back to car's home location for legacy bookings.
     */
    private Double pickupLongitude;

    /**
     * Full street address for pickup location.
     */
    private String pickupAddress;

    /**
     * City name for pickup location.
     */
    private String pickupCity;

    /**
     * Postal code for pickup location.
     */
    private String pickupZipCode;

    /**
     * Whether the pickup location is an estimate (car home location fallback).
     * True for legacy bookings without explicit pickup coordinates.
     */
    @Builder.Default
    private boolean pickupLocationEstimated = false;

    // ==================== LOCATION VARIANCE (Check-in Phase) ====================

    /**
     * Distance in meters between agreed pickup location and actual car location at check-in.
     * Null until host submits check-in photos with location.
     */
    private Integer pickupLocationVarianceMeters;

    /**
     * Variance status enum for UI badge display.
     * NONE (≤500m), WARNING (500m-2km), BLOCKING (>2km).
     */
    private LocationVarianceStatus varianceStatus;

    // ==================== DELIVERY INFO ====================

    /**
     * Calculated delivery distance in kilometers (from car home to pickup).
     * Null or 0 if guest picks up at car's home location.
     */
    private BigDecimal deliveryDistanceKm;

    /**
     * Delivery fee in RSD.
     * Null or 0 if no delivery required.
     */
    private BigDecimal deliveryFeeCalculated;

    /**
     * Location variance status for pickup location validation.
     * Used at check-in to indicate car position relative to agreed pickup.
     */
    public enum LocationVarianceStatus {
        /** Variance ≤500m - acceptable, no warning */
        NONE,
        /** Variance 500m-2km - show warning badge */
        WARNING,
        /** Variance >2km - may block check-in */
        BLOCKING
    }
}
