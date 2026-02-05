package org.example.rentoza.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.notification.UserDeviceToken;

/**
 * Request DTO for registering a Firebase Cloud Messaging device token.
 * Sent from frontend when user grants notification permission.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterDeviceTokenRequestDTO {

    @NotBlank(message = "Device token is required")
    private String deviceToken;

    @NotNull(message = "Platform is required")
    private UserDeviceToken.DevicePlatform platform;
}
