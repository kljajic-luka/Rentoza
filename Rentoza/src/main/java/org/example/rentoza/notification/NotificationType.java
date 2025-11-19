package org.example.rentoza.notification;

/**
 * Enumeration of notification types in the system.
 * Each type represents a different event that triggers a notification.
 */
public enum NotificationType {
    /**
     * Triggered when a booking is confirmed by the system (legacy instant booking).
     */
    BOOKING_CONFIRMED,

    /**
     * Triggered when a guest submits a booking request (sent to guest).
     */
    BOOKING_REQUEST_SENT,

    /**
     * Triggered when a host receives a new booking request (sent to owner).
     */
    BOOKING_REQUEST_RECEIVED,

    /**
     * Triggered when a host approves a booking request (sent to guest).
     */
    BOOKING_APPROVED,

    /**
     * Triggered when a host declines a booking request (sent to guest).
     */
    BOOKING_DECLINED,

    /**
     * Triggered when a pending booking request expires due to host inactivity.
     */
    BOOKING_EXPIRED,

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
