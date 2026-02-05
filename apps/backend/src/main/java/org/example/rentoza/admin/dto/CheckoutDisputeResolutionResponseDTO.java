package org.example.rentoza.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO for checkout damage dispute resolution (VAL-010).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutDisputeResolutionResponseDTO {
    
    private Long bookingId;
    private Long damageClaimId;
    private String decision;
    private BigDecimal originalClaimAmountRsd;
    private BigDecimal approvedAmountRsd;
    private BigDecimal depositReleasedRsd;
    private BigDecimal depositCapturedRsd;
    private String resolutionNotes;
    private Long resolvedByAdminId;
    private String resolvedByAdminName;
    private Instant resolvedAt;
    
    /**
     * Whether the checkout saga has been resumed after resolution.
     */
    private boolean sagaResumed;
    
    /**
     * Final booking status after resolution.
     */
    private String newBookingStatus;
    
    /**
     * Whether notifications were sent to parties.
     */
    private boolean notificationsSent;
}
