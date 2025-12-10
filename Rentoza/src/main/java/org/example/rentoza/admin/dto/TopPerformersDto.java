package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO for top performing hosts and cars.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopPerformersDto {
    
    private List<TopHost> topHosts;
    private List<TopCar> topCars;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopHost {
        private Long hostId;
        private String hostName;
        private Long bookingCount;
        private BigDecimal totalRevenue;
        private Double averageRating;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopCar {
        private Long carId;
        private String carMake;
        private String carModel;
        private Long bookingCount;
        private BigDecimal totalRevenue;
        private Double utilizationRate; // Percentage of days booked
    }
}
