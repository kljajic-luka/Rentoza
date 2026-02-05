package org.example.rentoza.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for admin document verification or rejection.
 * 
 * If rejecting: reason must be provided (min 20 chars for clarity).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVerificationRequestDto {
    
    /**
     * Approve or reject the document.
     * - true: mark as VERIFIED
     * - false: mark as REJECTED (requires reason)
     */
    @NotNull(message = "Verification action is required")
    private Boolean approved;
    
    /**
     * Rejection reason (required if approved = false).
     * Min 20 chars to ensure meaningful feedback to owner.
     */
    @Size(min = 20, max = 500, message = "Rejection reason must be 20-500 characters")
    private String rejectionReason;
}
