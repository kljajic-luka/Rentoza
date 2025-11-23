package org.example.rentoza.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerCarPreviewDTO {
    private Long id;
    private String brand;
    private String model;
    private int year;
    private String imageUrl;
    private Double pricePerDay;
    private Double rating;
    private int tripCount;
}
