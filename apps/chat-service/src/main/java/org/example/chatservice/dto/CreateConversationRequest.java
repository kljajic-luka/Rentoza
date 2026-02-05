package org.example.chatservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a new conversation.
 * 
 * <h3>Validation Strategy:</h3>
 * <ul>
 *   <li><strong>bookingId</strong>: Long/BIGINT, required (positive)</li>
 *   <li><strong>renterId</strong>: Long/BIGINT, required (positive)</li>
 *   <li><strong>ownerId</strong>: Long/BIGINT, required (positive)</li>
 *   <li><strong>initialMessage</strong>: String, optional</li>
 * </ul>
 * 
 * <h3>Enterprise-Grade Validation:</h3>
 * <ul>
 *   <li>@NotNull + @Positive on all numeric fields (bookingId, renterId, ownerId)</li>
 *   <li>Prevents negative/zero IDs (security)</li>
 *   <li>Clear validation error messages</li>
 * </ul>
 * 
 * @author Rentoza Development Team
 * @since 2.0.0 (Supabase Migration)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateConversationRequest {

    @NotNull(message = "Booking ID is required")
    @Positive(message = "Booking ID must be a positive number")
    private Long bookingId;

    @NotNull(message = "Renter ID is required")
    @Positive(message = "Renter ID must be a positive number")
    private Long renterId;

    @NotNull(message = "Owner ID is required")
    @Positive(message = "Owner ID must be a positive number")
    private Long ownerId;

    private String initialMessage;
}
