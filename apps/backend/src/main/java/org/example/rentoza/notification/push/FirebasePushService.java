package org.example.rentoza.notification.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for sending push notifications via Firebase Cloud Messaging.
 * Integrates Firebase Admin SDK for server-side push notifications.
 *
 * Requires FIREBASE_CREDENTIALS_PATH environment variable or configuration property.
 */
@Service
@Slf4j
public class FirebasePushService {

    @Value("${firebase.credentials.path:#{null}}")
    private String credentialsPath;

    private boolean initialized = false;

    /**
     * Initialize Firebase Admin SDK on application startup.
     * Gracefully handles missing credentials (logs warning instead of failing).
     */
    @PostConstruct
    public void initialize() {
        if (credentialsPath == null || credentialsPath.isBlank()) {
            log.warn("Firebase credentials path not configured. Push notifications will be disabled.");
            log.warn("Set 'firebase.credentials.path' property or FIREBASE_CREDENTIALS_PATH environment variable.");
            return;
        }

        try {
            FileInputStream serviceAccount = new FileInputStream(credentialsPath);

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options);
                initialized = true;
                log.info("Firebase Admin SDK initialized successfully");
            } else {
                initialized = true;
                log.info("Firebase Admin SDK already initialized");
            }

        } catch (IOException e) {
            log.error("Failed to initialize Firebase Admin SDK: {}", e.getMessage(), e);
            log.warn("Push notifications will be disabled");
        }
    }

    /**
     * Send push notification to a specific device.
     *
     * @param deviceToken FCM device token
     * @param title Notification title
     * @param body Notification body
     * @param relatedEntityId Optional deep link data
     */
    public void sendNotification(String deviceToken, String title, String body, String relatedEntityId) {
        if (!initialized) {
            log.debug("Firebase not initialized, skipping push notification");
            return;
        }

        try {
            // Build notification payload
            Notification notification = Notification.builder()
                    .setTitle(title)
                    .setBody(body)
                    .build();

            // Build data payload for deep linking
            Map<String, String> data = new HashMap<>();
            if (relatedEntityId != null && !relatedEntityId.isBlank()) {
                data.put("relatedEntityId", relatedEntityId);
                data.put("clickAction", buildDeepLink(relatedEntityId));
            }

            // Build message
            Message message = Message.builder()
                    .setToken(deviceToken)
                    .setNotification(notification)
                    .putAllData(data)
                    .setWebpushConfig(buildWebPushConfig(title, body))
                    .build();

            // Send message
            String response = FirebaseMessaging.getInstance().send(message);
            log.debug("Successfully sent push notification: {}", response);

        } catch (FirebaseMessagingException e) {
            handleMessagingException(e, deviceToken);
        } catch (Exception e) {
            log.error("Unexpected error sending push notification: {}", e.getMessage(), e);
        }
    }

    /**
     * Send push notification to multiple devices (batch).
     *
     * @param deviceTokens List of FCM device tokens
     * @param title Notification title
     * @param body Notification body
     * @param relatedEntityId Optional deep link data
     */
    public void sendMulticastNotification(
            java.util.List<String> deviceTokens,
            String title,
            String body,
            String relatedEntityId) {

        if (!initialized || deviceTokens.isEmpty()) {
            return;
        }

        try {
            Notification notification = Notification.builder()
                    .setTitle(title)
                    .setBody(body)
                    .build();

            Map<String, String> data = new HashMap<>();
            if (relatedEntityId != null && !relatedEntityId.isBlank()) {
                data.put("relatedEntityId", relatedEntityId);
                data.put("clickAction", buildDeepLink(relatedEntityId));
            }

            MulticastMessage message = MulticastMessage.builder()
                    .setNotification(notification)
                    .putAllData(data)
                    .addAllTokens(deviceTokens)
                    .setWebpushConfig(buildWebPushConfig(title, body))
                    .build();

            BatchResponse response = FirebaseMessaging.getInstance().sendEachForMulticast(message);
            log.debug("Multicast push sent. Success: {}, Failure: {}",
                    response.getSuccessCount(),
                    response.getFailureCount());

            // Handle failed tokens
            if (response.getFailureCount() > 0) {
                for (int i = 0; i < response.getResponses().size(); i++) {
                    SendResponse sr = response.getResponses().get(i);
                    if (!sr.isSuccessful()) {
                        log.warn("Failed to send to token {}: {}",
                                deviceTokens.get(i),
                                sr.getException().getMessage());
                    }
                }
            }

        } catch (FirebaseMessagingException e) {
            log.error("Failed to send multicast notification: {}", e.getMessage(), e);
        }
    }

    /**
     * Build WebPush-specific configuration for browser notifications.
     */
    private WebpushConfig buildWebPushConfig(String title, String body) {
        return WebpushConfig.builder()
                .setNotification(WebpushNotification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .setIcon("/assets/icons/icon-192x192.png")
                        .setBadge("/assets/icons/badge-72x72.png")
                        .build())
                .build();
    }

    /**
     * Build deep link URL based on entity ID.
     */
    private String buildDeepLink(String relatedEntityId) {
        // Simple heuristic based on entity ID format
        if (relatedEntityId.startsWith("booking-")) {
            return "/bookings/" + relatedEntityId.substring(8);
        } else if (relatedEntityId.startsWith("review-")) {
            return "/reviews/" + relatedEntityId.substring(7);
        } else if (relatedEntityId.startsWith("message-")) {
            return "/chat";
        }
        return "/notifications";
    }

    /**
     * Handle Firebase Messaging exceptions and log appropriately.
     */
    private void handleMessagingException(FirebaseMessagingException e, String deviceToken) {
        String errorCode = e.getMessagingErrorCode() != null
                ? e.getMessagingErrorCode().name()
                : "UNKNOWN";

        switch (errorCode) {
            case "INVALID_ARGUMENT", "UNREGISTERED" -> {
                log.warn("Invalid or unregistered device token: {}. Token should be removed.", deviceToken);
                // TODO: Emit event to remove invalid token from database
            }
            case "QUOTA_EXCEEDED" -> log.error("FCM quota exceeded. Consider upgrading Firebase plan.");
            case "UNAVAILABLE" -> log.warn("FCM service temporarily unavailable. Will retry automatically.");
            default -> log.error("FCM error ({}): {}", errorCode, e.getMessage());
        }
    }

    /**
     * Check if Firebase is initialized and ready to send notifications.
     */
    public boolean isInitialized() {
        return initialized;
    }
}
