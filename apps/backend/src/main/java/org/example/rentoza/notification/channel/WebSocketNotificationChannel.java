package org.example.rentoza.notification.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.notification.Notification;
import org.example.rentoza.notification.dto.NotificationResponseDTO;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * WebSocket channel for real-time in-app notifications.
 * Delivers notifications via STOMP to /user/{userId}/queue/notifications.
 *
 * Reuses existing WebSocket infrastructure from chat service.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketNotificationChannel implements NotificationChannel {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void send(Notification notification) {
        try {
            NotificationResponseDTO dto = NotificationResponseDTO.fromEntity(notification);

            messagingTemplate.convertAndSendToUser(
                    String.valueOf(notification.getRecipient().getId()),
                    "/queue/notifications",
                    dto);

            log.debug("WebSocket notification sent to user {} for type {}",
                    notification.getRecipient().getId(),
                    notification.getType());
        } catch (Exception e) {
            log.error("Failed to send WebSocket notification to user {}: {}",
                    notification.getRecipient().getId(),
                    e.getMessage(), e);
        }
    }

    @Override
    public String getChannelName() {
        return "WebSocket";
    }

    @Override
    public boolean isEnabled() {
        return messagingTemplate != null;
    }
}
