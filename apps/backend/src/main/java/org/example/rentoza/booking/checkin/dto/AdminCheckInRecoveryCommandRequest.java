package org.example.rentoza.booking.checkin.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminCheckInRecoveryCommandRequest(
        @NotBlank(message = "Reason is required") String reason
) {
}
