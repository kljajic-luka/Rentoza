package org.example.rentoza.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.notification.NotificationType;

/**
 * Request DTO for creating a new notification.
 * Used internally by services to create notifications.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateNotificationRequestDTO {

    @NotNull(message = "Recipient ID is required")
    private Long recipientId;

    @NotNull(message = "Notification type is required")
    private NotificationType type;

    @NotBlank(message = "Message cannot be empty")
    private String message;

    /**
     * Optional reference to the entity that triggered this notification.
     * Examples: bookingId, messageId, reviewId
     */
    private String relatedEntityId;
}
