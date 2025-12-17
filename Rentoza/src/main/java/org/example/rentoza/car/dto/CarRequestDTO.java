package org.example.rentoza.car.dto;

import lombok.Getter;
import lombok.Setter;
import org.example.rentoza.car.*;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class CarRequestDTO {
    private String brand;
    private String model;
    private Integer year;
    private BigDecimal pricePerDay;
    private String location;
    private String imageUrl;
    private List<String> imageUrls;
    private String ownerEmail;

    // Geospatial location fields (Phase 2.4) - REQUIRED for new cars
    private BigDecimal locationLatitude;
    private BigDecimal locationLongitude;
    private String locationAddress;
    private String locationCity;
    private String locationZipCode;

    // New production-ready fields
    // License plate validation: Must start with 2 letters, max 11 total characters
    // Supports flexible Serbian plate formats: BG-123-AB, BG123AB, BGABC1234, etc.
    @Pattern(regexp = "^[A-Z]{2}[A-Z0-9]{0,9}$", message = "License plate must start with 2 uppercase letters and contain max 11 characters total")
    private String licensePlate;
    private String description;
    private Integer seats;
    private FuelType fuelType;
    private Double fuelConsumption;
    private TransmissionType transmissionType;
    private List<Feature> features;
    private List<String> addOns;
    private CancellationPolicy cancellationPolicy;
    private Integer minRentalDays;
    private Integer maxRentalDays;
}