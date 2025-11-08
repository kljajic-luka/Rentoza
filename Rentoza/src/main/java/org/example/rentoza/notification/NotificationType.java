package org.example.rentoza.notification;

/**
 * Enumeration of notification types in the system.
 * Each type represents a different event that triggers a notification.
 */
public enum NotificationType {
    /**
     * Triggered when a booking is confirmed by the system.
     */
    BOOKING_CONFIRMED,

    /**
     * Triggered when a booking is cancelled by either party.
     */
    BOOKING_CANCELLED,

    /**
     * Triggered when a new chat message is received (for offline/inactive users).
     */
    NEW_MESSAGE,

    /**
     * Triggered when a user receives a new review.
     */
    REVIEW_RECEIVED
}
