package org.example.rentoza.notification.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.notification.Notification;
import org.example.rentoza.notification.UserDeviceToken;
import org.example.rentoza.notification.UserDeviceTokenRepository;
import org.example.rentoza.notification.push.FirebasePushService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Firebase Cloud Messaging channel for push notifications.
 * Sends push notifications to all registered devices for a user.
 *
 * Can be disabled via configuration property.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FirebasePushNotificationChannel implements NotificationChannel {

    private final FirebasePushService firebasePushService;
    private final UserDeviceTokenRepository deviceTokenRepository;

    @Value("${notifications.push.enabled:true}")
    private boolean enabled;

    @Override
    public void send(Notification notification) {
        if (!isEnabled()) {
            log.debug("Push notifications disabled, skipping notification {}", notification.getId());
            return;
        }

        try {
            Long userId = notification.getRecipient().getId();
            List<UserDeviceToken> deviceTokens = deviceTokenRepository.findByUserId(userId);

            if (deviceTokens.isEmpty()) {
                log.debug("No device tokens found for user {}, skipping push notification", userId);
                return;
            }

            String title = getTitleForType(notification.getType().name());
            String body = notification.getMessage();

            // Send to all devices
            for (UserDeviceToken deviceToken : deviceTokens) {
                firebasePushService.sendNotification(
                        deviceToken.getDeviceToken(),
                        title,
                        body,
                        notification.getRelatedEntityId()
                );
            }

            log.debug("Push notifications sent to {} devices for user {}",
                    deviceTokens.size(),
                    userId);
        } catch (Exception e) {
            log.error("Failed to send push notification to user {}: {}",
                    notification.getRecipient().getId(),
                    e.getMessage(), e);
        }
    }

    @Override
    public String getChannelName() {
        return "Firebase Push";
    }

    @Override
    public boolean isEnabled() {
        return enabled && firebasePushService != null && firebasePushService.isInitialized();
    }

    /**
     * Generate push notification title based on notification type.
     */
    private String getTitleForType(String type) {
        return switch (type) {
            case "BOOKING_CONFIRMED" -> "Rezervacija potvrđena";
            case "BOOKING_CANCELLED" -> "Rezervacija otkazana";
            case "NEW_MESSAGE" -> "Nova poruka";
            case "REVIEW_RECEIVED" -> "Nova recenzija";
            default -> "Rentoza obaveštenje";
        };
    }
}
