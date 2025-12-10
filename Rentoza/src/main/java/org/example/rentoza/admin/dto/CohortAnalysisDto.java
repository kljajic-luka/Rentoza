package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;

/**
 * DTO for user cohort analysis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CohortAnalysisDto {
    
    private YearMonth cohort; // Month user signed up
    private Long totalUsers;
    private Map<Integer, RetentionMetrics> retentionByMonth; // Month offset -> metrics
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetentionMetrics {
        private Long activeUsers;
        private Double retentionRate; // Percentage
        private BigDecimal revenueGenerated;
        private Long bookingCount;
    }
}
