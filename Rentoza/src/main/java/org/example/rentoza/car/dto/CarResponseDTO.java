package org.example.rentoza.car.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.example.rentoza.car.*;
import org.example.rentoza.common.GeoPoint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Getter
@Setter
@AllArgsConstructor
public class CarResponseDTO {

    // Privacy obfuscation radius in meters (±500m)
    private static final int OBFUSCATION_RADIUS_METERS = 500;
    private static final Random OBFUSCATION_RANDOM = new Random();

    private Long id;
    private String brand;
    private String model;
    private Integer year;
    private BigDecimal pricePerDay;
    private String location;  // City name only for non-owners/non-bookers
    private String imageUrl;
    private boolean available;

    // Geospatial location fields (Phase 2.4 - Privacy Controlled)
    private BigDecimal locationLatitude;   // Fuzzy or exact based on isExactLocation
    private BigDecimal locationLongitude;  // Fuzzy or exact based on isExactLocation
    private String locationAddress;        // null for non-owners/non-bookers, exact for owners
    private String locationCity;           // Always visible (city name)
    private boolean isExactLocation;       // Frontend uses this to render exact pin vs fuzzy circle
    
    // Owner Info (Privacy Safe)
    private Long ownerId;
    private String ownerFirstName;
    private String ownerLastInitial;
    private String ownerAvatarUrl;
    private String ownerJoinDate;
    private Double ownerRating;     // Populated in detailed view
    private Integer ownerTripCount; // Populated in detailed view

    // New production-ready fields
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
    private List<String> imageUrls;

    public CarResponseDTO(Car car) {
        this(car, false, null); // Default: no exact location access
    }

    /**
     * Constructor with privacy control.
     * 
     * @param car The car entity
     * @param isOwnerOrActiveBooker True if viewer is the car owner or has an active booking
     * @param currentUserId The ID of the current user (null if anonymous)
     */
    public CarResponseDTO(Car car, boolean isOwnerOrActiveBooker, Long currentUserId) {
        this.id = car.getId();
        this.brand = car.getBrand();
        this.model = car.getModel();
        this.year = car.getYear();
        this.pricePerDay = car.getPricePerDay();
        this.imageUrl = car.getImageUrl();
        this.available = car.isAvailable();

        // Check if current user is the owner
        boolean isOwner = currentUserId != null && 
                          car.getOwner() != null && 
                          car.getOwner().getId().equals(currentUserId);

        // Privacy Logic: Owner or active booker sees exact location, others see fuzzy
        this.isExactLocation = isOwner || isOwnerOrActiveBooker;

        GeoPoint geoPoint = car.getLocationGeoPoint();
        if (geoPoint != null) {
            this.locationCity = geoPoint.getCity();
            
            if (this.isExactLocation) {
                // EXACT: Owner or active booker - show precise location
                this.locationLatitude = geoPoint.getLatitude();
                this.locationLongitude = geoPoint.getLongitude();
                this.locationAddress = geoPoint.getAddress();
                this.location = geoPoint.getAddress() != null 
                    ? geoPoint.getAddress() 
                    : capitalizeLocation(car.getLocation());
            } else {
                // FUZZY: Public view - obfuscate coordinates, show city only
                GeoPoint fuzzy = geoPoint.obfuscate(OBFUSCATION_RANDOM, OBFUSCATION_RADIUS_METERS);
                this.locationLatitude = fuzzy.getLatitude();
                this.locationLongitude = fuzzy.getLongitude();
                this.locationAddress = null;  // Hide exact address
                this.location = this.locationCity != null 
                    ? capitalizeLocation(this.locationCity) 
                    : extractCityFromLegacyLocation(car.getLocation());
            }
        } else {
            // Fallback to legacy location field - extract city only for privacy
            String cityName = extractCityFromLegacyLocation(car.getLocation());
            this.location = capitalizeLocation(cityName);
            this.locationCity = cityName;
            this.locationLatitude = null;
            this.locationLongitude = null;
            this.locationAddress = null;
            this.isExactLocation = false;
        }

        // New fields
        // License plate is NOT exposed in public DTO for security
        // this.licensePlate = car.getLicensePlate(); 
        this.description = car.getDescription();
        this.seats = car.getSeats();
        this.fuelType = car.getFuelType();
        this.fuelConsumption = car.getFuelConsumption();
        this.transmissionType = car.getTransmissionType();
        // Convert Set to List for JSON array serialization (maintains consistent API contract)
        this.features = car.getFeatures() != null ? new ArrayList<>(car.getFeatures()) : List.of();
        this.addOns = car.getAddOns() != null ? new ArrayList<>(car.getAddOns()) : List.of();
        this.cancellationPolicy = car.getCancellationPolicy();
        this.minRentalDays = car.getMinRentalDays();
        this.maxRentalDays = car.getMaxRentalDays();
        this.imageUrls = car.getImageUrls() != null ? List.copyOf(car.getImageUrls()) : List.of();

        if (car.getOwner() != null) {
            this.ownerId = car.getOwner().getId();
            this.ownerFirstName = car.getOwner().getFirstName();
            this.ownerLastInitial = formatLastInitial(car.getOwner().getLastName());
            this.ownerAvatarUrl = car.getOwner().getAvatarUrl();
            this.ownerJoinDate = formatJoinDate(car.getOwner().getCreatedAt());
        }
    }

    private String formatLastInitial(String lastName) {
        if (lastName == null || lastName.isEmpty()) {
            return "";
        }
        return lastName.charAt(0) + ".";
    }

    private String formatJoinDate(java.time.Instant createdAt) {
        if (createdAt == null) return "";
        return org.example.rentoza.config.timezone.SerbiaTimeZone.toLocalDateTime(createdAt)
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", java.util.Locale.ENGLISH));
    }

    /**
     * Capitalize first letter of each word in location
     * E.g., "novi sad" -> "Novi Sad"
     */
    private String capitalizeLocation(String location) {
        if (location == null || location.isEmpty()) {
            return location;
        }

        String[] words = location.split("\\s+");
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(" ");
            }
            String word = words[i];
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1).toLowerCase());
                }
            }
        }

        return result.toString();
    }

    /**
     * Extract city name from legacy location string for privacy protection.
     * 
     * <p>Legacy location format example: 
     * "34, marije mage magazinović, centar, rosulje, užice, gradska opština užice"
     * 
     * <p>City is typically near the end, before municipality/region parts.
     * We skip administrative parts like "gradska opština...", "opština...", "okrug...".
     * 
     * @param location The legacy full address string
     * @return The extracted city name, or "Srbija" as fallback
     */
    private String extractCityFromLegacyLocation(String location) {
        if (location == null || location.isBlank()) {
            return "Srbija";  // Ultimate fallback
        }
        
        String[] parts = location.split(",");
        
        // Try to find city - usually near the end, skip municipality parts
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].trim().toLowerCase();
            // Skip administrative parts
            if (!part.contains("opština") && 
                !part.contains("okrug") && 
                !part.contains("gradska") &&
                !part.isBlank()) {
                return parts[i].trim();
            }
        }
        
        return "Srbija";  // Ultimate fallback
    }
}
