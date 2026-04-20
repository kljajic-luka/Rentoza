package org.example.rentoza.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

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
    private BigDecimal pricePerDay;
    private Double rating;
    private int tripCount;
}
