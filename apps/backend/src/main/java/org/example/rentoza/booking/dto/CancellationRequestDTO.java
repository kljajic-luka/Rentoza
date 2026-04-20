package org.example.rentoza.booking.dto;

import jakarta.validation.constraints.NotNull;
import org.example.rentoza.booking.cancellation.CancellationReason;

/**
 * Request DTO for initiating a cancellation.
 * 
 * @param reason the reason for cancellation (required)
 * @param notes optional free-text notes from the user
 * @since 2024-01 (Cancellation Policy Migration - Phase 2)
 */
public record CancellationRequestDTO(
    @NotNull(message = "Cancellation reason is required")
    CancellationReason reason,
    
    String notes
) {}
