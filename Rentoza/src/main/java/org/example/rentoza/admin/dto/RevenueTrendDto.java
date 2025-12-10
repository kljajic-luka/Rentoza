package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO for revenue trend analytics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueTrendDto {
    
    private List<DataPoint> dataPoints;
    private BigDecimal totalRevenue;
    private BigDecimal averagePerPeriod;
    private Double growthRate; // Percentage
    private String period; // DAILY, WEEKLY, MONTHLY
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataPoint {
        private LocalDate date;
        private BigDecimal revenue;
        private Long bookingCount;
    }
}
