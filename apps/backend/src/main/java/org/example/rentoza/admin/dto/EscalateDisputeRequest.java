package org.example.rentoza.admin.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class EscalateDisputeRequest {
    @NotBlank(message = "Reason is required")
    private String reason;
}
