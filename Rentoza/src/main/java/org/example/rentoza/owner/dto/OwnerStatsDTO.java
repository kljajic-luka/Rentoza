package org.example.rentoza.owner.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
    private Double monthlyEarnings;
    private Double averageRating;
}
