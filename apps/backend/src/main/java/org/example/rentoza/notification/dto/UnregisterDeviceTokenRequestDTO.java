package org.example.rentoza.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for unregistering a device token.
 * Used when user logs out or revokes notification permission.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnregisterDeviceTokenRequestDTO {

    @NotBlank(message = "Device token is required")
    private String deviceToken;
}
