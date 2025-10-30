package org.example.rentoza.car.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.example.rentoza.car.*;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class CarResponseDTO {

    private Long id;
    private String brand;
    private String model;
    private Integer year;
    private Double pricePerDay;
    private String location;
    private String imageUrl;
    private boolean available;
    private String ownerFullName;
    private String ownerEmail;

    // New production-ready fields
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
        this.location = car.getLocation();
        this.imageUrl = car.getImageUrl();
        this.available = car.isAvailable();

        // New fields
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
            String firstName = car.getOwner().getFirstName();
            String lastName = car.getOwner().getLastName();
            this.ownerFullName = (firstName != null ? firstName : "") +
                    (lastName != null ? " " + lastName : "");
            this.ownerEmail = car.getOwner().getEmail();
        } else {
            this.ownerFullName = null;
            this.ownerEmail = null;
        }
    }
}
