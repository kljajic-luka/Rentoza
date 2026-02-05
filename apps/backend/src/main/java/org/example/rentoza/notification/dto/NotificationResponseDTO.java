package org.example.rentoza.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.notification.Notification;
import org.example.rentoza.notification.NotificationType;

import java.time.Instant;

/**
 * Response DTO for notification information.
 * Lightweight DTO to minimize network payload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponseDTO {

    private Long id;
    private NotificationType type;
    private String message;
    private String relatedEntityId;
    private boolean read;
    private Instant createdAt;

    /**
     * Convert entity to DTO.
     */
    public static NotificationResponseDTO fromEntity(Notification notification) {
        return NotificationResponseDTO.builder()
                .id(notification.getId())
                .type(notification.getType())
                .message(notification.getMessage())
                .relatedEntityId(notification.getRelatedEntityId())
                .read(notification.isRead())
                .createdAt(notification.getCreatedAt())
                .build();
    }
}
