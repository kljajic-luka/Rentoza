package org.example.rentoza.notification.channel;

import org.example.rentoza.notification.Notification;

/**
 * Strategy interface for notification delivery channels.
 * Enables pluggable notification delivery (WebSocket, Email, Push).
 *
 * Implementations should handle their specific delivery mechanism
 * and return silently if delivery fails (logging errors internally).
 */
public interface NotificationChannel {

    /**
     * Send a notification through this channel.
     *
     * @param notification The notification to send
     */
    void send(Notification notification);

    /**
     * Get the channel name for logging and monitoring.
     *
     * @return Channel name (e.g., "WebSocket", "Email", "FCM")
     */
    String getChannelName();

    /**
     * Check if this channel is enabled and properly configured.
     *
     * @return true if the channel can send notifications
     */
    boolean isEnabled();
}
