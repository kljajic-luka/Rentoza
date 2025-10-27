package org.example.rentoza.car.dto;

import lombok.Getter;
import lombok.Setter;
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
}