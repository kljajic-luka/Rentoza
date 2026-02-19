package org.example.rentoza.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for admin force-completing a booking.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ForceCompleteBookingRequest {
    
    @NotBlank(message = "Reason for force-completion is required")
    @Size(min = 10, max = 500, message = "Reason must be 10-500 characters")
    private String reason;
}
