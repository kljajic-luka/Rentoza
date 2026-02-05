package org.example.rentoza.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for resolving check-in disputes (VAL-004).
 * 
 * <p>Admin can choose from three resolution options:
 * <ul>
 *   <li>PROCEED - Proceed with documented damage (guest not liable)</li>
 *   <li>CANCEL - Cancel booking with full refund to guest</li>
 *   <li>DECLINE - Decline dispute (no undisclosed damage found)</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckInDisputeResolutionDTO {

    /**
     * Resolution decision.
     */
    @NotNull(message = "Odluka je obavezna")
    private CheckInDisputeDecision decision;

    /**
     * Admin notes explaining the decision.
     */
    @Size(max = 2000, message = "Napomena može imati maksimum 2000 karaktera")
    private String notes;

    /**
     * Documented damage to record (required for PROCEED decision).
     * This will be noted on the booking to waive guest liability.
     */
    @Size(max = 2000, message = "Opis štete može imati maksimum 2000 karaktera")
    private String documentedDamage;

    /**
     * Cancellation reason (required for CANCEL decision).
     */
    @Size(max = 500, message = "Razlog otkazivanja može imati maksimum 500 karaktera")
    private String cancellationReason;

    /**
     * Check-in dispute resolution options.
     */
    public enum CheckInDisputeDecision {
        /**
         * Proceed with booking, damage documented, guest not liable.
         */
        PROCEED,
        
        /**
         * Cancel booking with full refund to guest.
         */
        CANCEL,
        
        /**
         * Decline dispute - no undisclosed damage found.
         * Guest must accept condition or self-cancel.
         */
        DECLINE
    }
}
