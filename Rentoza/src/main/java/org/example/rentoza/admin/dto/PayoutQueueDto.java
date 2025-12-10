package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO for payout queue items awaiting processing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutQueueDto {
    
    private Long bookingId;
    private Long hostId;
    private String hostName;
    private String hostEmail;
    private BigDecimal amountCents;
    private String currency;
    private Instant bookingCompletedAt;
    private Instant payoutScheduledFor;
    private String status; // PENDING, PROCESSING, COMPLETED, FAILED
    private Integer retryCount;
    private String failureReason;
    private String paymentReference;
}
