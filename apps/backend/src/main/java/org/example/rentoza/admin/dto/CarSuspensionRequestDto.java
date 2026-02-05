package org.example.rentoza.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for car suspension operations.
 * 
 * <p>Replaces raw Map<String, String> for proper validation.
 * Ensures suspension reason is always provided with adequate length.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarSuspensionRequestDto {
    
    /**
     * Reason for suspension (required).
     * Must be detailed enough to inform the car owner.
     */
    @NotBlank(message = "Suspension reason is required")
    @Size(min = 10, max = 500, message = "Reason must be 10-500 characters")
    private String reason;
}
