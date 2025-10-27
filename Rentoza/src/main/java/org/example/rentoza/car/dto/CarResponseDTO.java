package org.example.rentoza.car.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.example.rentoza.car.Car;

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

    public CarResponseDTO(Car car) {
        this.id = car.getId();
        this.brand = car.getBrand();
        this.model = car.getModel();
        this.year = car.getYear();
        this.pricePerDay = car.getPricePerDay();
        this.location = car.getLocation();
        this.imageUrl = car.getImageUrl();
        this.available = car.isAvailable();

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