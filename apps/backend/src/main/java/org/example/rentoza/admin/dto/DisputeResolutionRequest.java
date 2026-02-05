package org.example.rentoza.admin.dto;

import lombok.Data;
import org.example.rentoza.admin.dto.enums.DisputeDecision;
import jakarta.validation.constraints.AssertTrue;

@Data
public class DisputeResolutionRequest {
    private DisputeDecision decision;
    private Long approvedAmount;      // In cents
    private String rejectionReason;   // Why rejected
    private String notes;             // Admin notes
    
    @AssertTrue(message = "APPROVED decision must have approvedAmount")
    public boolean isValid() {
        if (decision == DisputeDecision.APPROVED) {
            return approvedAmount != null && approvedAmount > 0;
        }
        return true;
    }
}
