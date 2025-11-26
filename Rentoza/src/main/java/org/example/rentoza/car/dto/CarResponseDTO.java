package org.example.rentoza.car.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.example.rentoza.car.*;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class CarResponseDTO {

    private Long id;
    private String brand;
    private String model;
    private Integer year;
    private BigDecimal pricePerDay;
    private String location;
    private String imageUrl;
    private boolean available;
    
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
        this.id = car.getId();
        this.brand = car.getBrand();
        this.model = car.getModel();
        this.year = car.getYear();
        this.pricePerDay = car.getPricePerDay();
        this.location = capitalizeLocation(car.getLocation());
        this.imageUrl = car.getImageUrl();
        this.available = car.isAvailable();

        // New fields
        // License plate is NOT exposed in public DTO for security
        // this.licensePlate = car.getLicensePlate(); 
        this.description = car.getDescription();
        this.seats = car.getSeats();
        this.fuelType = car.getFuelType();
        this.fuelConsumption = car.getFuelConsumption();
        this.transmissionType = car.getTransmissionType();
        this.features = car.getFeatures() != null ? List.copyOf(car.getFeatures()) : List.of();
        this.addOns = car.getAddOns() != null ? List.copyOf(car.getAddOns()) : List.of();
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
        return java.time.LocalDateTime.ofInstant(createdAt, java.time.ZoneId.systemDefault())
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
}
