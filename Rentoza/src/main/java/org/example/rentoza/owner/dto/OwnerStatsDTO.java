package org.example.rentoza.owner.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * DTO for owner dashboard statistics
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OwnerStatsDTO {
    private Integer totalCars;
    private Integer totalBookings;
    private BigDecimal monthlyEarnings;
    private Double averageRating;
}
