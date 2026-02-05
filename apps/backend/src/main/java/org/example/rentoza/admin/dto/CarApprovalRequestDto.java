package org.example.rentoza.admin.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for car approval/rejection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarApprovalRequestDto {
    
    /**
     * Approval decision: true = approve, false = reject.
     */
    private boolean approved;
    
    /**
     * Reason for rejection (required for reject/suspend actions).
     * Validated at controller level since it's only required for non-approve actions.
     */
    @Size(min = 10, max = 500, message = "Reason must be 10-500 characters")
    private String reason;
    
    /**
     * Admin notes (optional, stored in audit log).
     */
    @Size(max = 1000, message = "Notes must be at most 1000 characters")
    private String notes;
}
