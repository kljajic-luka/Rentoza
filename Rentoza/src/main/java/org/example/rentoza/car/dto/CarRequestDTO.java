package org.example.rentoza.car.dto;

import lombok.Getter;
import lombok.Setter;
import org.example.rentoza.car.*;

import java.util.List;

@Getter
@Setter
public class CarRequestDTO {
    private String brand;
    private String model;
    private Integer year;
    private Double pricePerDay;
    private String location;
    private String imageUrl;
    private List<String> imageUrls;
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
}