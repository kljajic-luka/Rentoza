package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result DTO for batch payout operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchPayoutResult {
    
    private Integer totalRequested;
    private Integer successCount;
    private Integer failureCount;
    private BigDecimal totalAmountProcessed;
    private List<PayoutFailure> failures;
    private String batchReference;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PayoutFailure {
        private Long bookingId;
        private String reason;
        private String errorCode;
    }
}
