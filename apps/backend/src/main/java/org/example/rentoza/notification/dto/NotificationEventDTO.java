package org.example.rentoza.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rentoza.notification.NotificationType;

import java.util.List;

/**
 * Internal event DTO for publishing notification events.
 * Used by NotificationEventPublisher to trigger multi-channel delivery.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEventDTO {

    /**
     * List of recipient user IDs.
     * Supports batch notifications (e.g., notify both renter and owner).
     */
    private List<Long> recipientIds;

    private NotificationType type;

    private String message;

    /**
     * Reference to the entity that triggered this notification.
     */
    private String relatedEntityId;

    /**
     * Email-specific content (optional).
     * If provided, email notification will use this template data.
     */
    private EmailTemplateData emailData;

    /**
     * Push notification-specific data (optional).
     * Used for deep linking and rich notifications.
     */
    private PushNotificationData pushData;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmailTemplateData {
        private String subject;
        private String templateName; // e.g., "booking-confirmed"
        private Object templateVariables; // Dynamic data for template
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PushNotificationData {
        private String title;
        private String body;
        private String clickAction; // Deep link URL
        private String icon;
    }
}
