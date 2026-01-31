package org.example.rentoza.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for admin resolution of checkout damage disputes (VAL-010).
 * 
 * <h2>Decision Options</h2>
 * <ul>
 *   <li><b>APPROVE:</b> Host's damage claim approved - deposit captured</li>
 *   <li><b>REJECT:</b> Host's claim rejected - deposit released to guest</li>
 *   <li><b>PARTIAL:</b> Partial approval - reduced damage amount</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutDisputeResolutionDTO {
    
    /**
     * Admin's decision for the dispute.
     */
    public enum Decision {
        /**
         * Approve host's damage claim in full.
         * Deposit captured for damage payment.
         */
        APPROVE,
        
        /**
         * Reject host's damage claim.
         * Deposit released back to guest.
         */
        REJECT,
        
        /**
         * Partial approval with reduced amount.
         * Requires approvedAmount to be set.
         */
        PARTIAL
    }
    
    @NotNull(message = "Decision is required")
    private Decision decision;
    
    /**
     * Approved damage amount (required for PARTIAL, optional for APPROVE).
     * For REJECT, this should be null or zero.
     */
    @PositiveOrZero(message = "Approved amount cannot be negative")
    private BigDecimal approvedAmountRsd;
    
    /**
     * Admin's notes explaining the resolution decision.
     * Required for REJECT and PARTIAL decisions.
     */
    @Size(max = 2000, message = "Resolution notes cannot exceed 2000 characters")
    private String resolutionNotes;
    
    /**
     * Whether to notify parties via email/push.
     */
    @Builder.Default
    private boolean notifyParties = true;
}
