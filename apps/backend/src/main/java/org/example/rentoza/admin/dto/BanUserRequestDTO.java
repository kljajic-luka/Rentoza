package org.example.rentoza.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for banning a user.
 * 
 * Requires a reason for audit trail and accountability.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BanUserRequestDTO {

    /**
     * Reason for the ban (required for audit trail).
     * Examples:
     * - "Multiple fraudulent booking attempts"
     * - "Harassment of other users"
     * - "Violation of terms of service"
     */
    @NotBlank(message = "Ban reason is required")
    @Size(min = 10, max = 500, message = "Ban reason must be between 10 and 500 characters")
    private String reason;
}
