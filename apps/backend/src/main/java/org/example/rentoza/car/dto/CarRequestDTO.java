package org.example.rentoza.car.dto;

import lombok.Getter;
import lombok.Setter;
import org.example.rentoza.car.*;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class CarRequestDTO {
    @NotBlank(message = "Brand is required")
    private String brand;

    @NotBlank(message = "Model is required")
    private String model;

    @NotNull(message = "Year is required")
    @Min(value = 1950, message = "Year must be at least 1950")
    @Max(value = 2050, message = "Year must be at most 2050")
    private Integer year;

    @NotNull(message = "Price per day is required")
    @DecimalMin(value = "10", message = "Price must be at least 10 RSD")
    @DecimalMax(value = "50000", message = "Price must be at most 50,000 RSD")
    private BigDecimal pricePerDay;

    private String location;
    private String imageUrl;

    @Size(max = 10, message = "Maximum 10 image URLs allowed")
    private List<String> imageUrls;
    private String ownerEmail;

    // Geospatial location fields (Phase 2.4) - REQUIRED for new cars
    @NotNull(message = "Location latitude is required")
    private BigDecimal locationLatitude;

    @NotNull(message = "Location longitude is required")
    private BigDecimal locationLongitude;

    private String locationAddress;
    private String locationCity;
    private String locationZipCode;

    // License plate validation: Serbian format XX-NNN-XX or XX-NNNN-XX (strict Turo standard)
    @Pattern(regexp = "^[A-Z]{2}-\\d{3,4}-[A-Z]{2}$", message = "License plate must be in Serbian format: XX-1234-YY")
    private String licensePlate;

    @Size(max = 1000, message = "Description must be 1000 characters or less")
    private String description;

    @Min(value = 2, message = "Minimum 2 seats")
    @Max(value = 9, message = "Maximum 9 seats")
    private Integer seats;

    private FuelType fuelType;
    private Double fuelConsumption;
    private TransmissionType transmissionType;
    private List<Feature> features;
    private List<String> addOns;
    private CancellationPolicy cancellationPolicy;

    @Min(value = 1, message = "Minimum rental days must be at least 1")
    private Integer minRentalDays;

    @Min(value = 1, message = "Maximum rental days must be at least 1")
    @Max(value = 30, message = "Maximum rental days must be at most 30")
    private Integer maxRentalDays;

    // ========== TURO-STANDARD FIELDS (Feature 3 Hardening) ==========

    /**
     * Host-configurable daily mileage limit in kilometers.
     * Default: 200 km/day. Range: 50-1000 km/day.
     */
    @Min(value = 50, message = "Daily mileage limit must be at least 50 km")
    @Max(value = 1000, message = "Daily mileage limit must be at most 1000 km")
    private Integer dailyMileageLimitKm;

    /**
     * Current vehicle mileage (odometer reading) in km.
     * Turo standard: max 300,000 km.
     */
    @Min(value = 0, message = "Current mileage cannot be negative")
    @Max(value = 300000, message = "Current mileage too high. Max 300,000 km.")
    private Integer currentMileageKm;

    /**
     * Whether instant booking is enabled (vs request-to-book).
     */
    private Boolean instantBookEnabled;
}