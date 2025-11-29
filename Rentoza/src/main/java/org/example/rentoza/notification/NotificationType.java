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
    REVIEW_RECEIVED,

    // ==========================================================================
    // CHECK-IN HANDSHAKE NOTIFICATIONS
    // ==========================================================================

    /**
     * Triggered when the check-in window opens (T-24h before trip start).
     * Sent to both host and guest.
     */
    CHECK_IN_WINDOW_OPENED,

    /**
     * Triggered as a reminder to complete check-in (T-12h before trip start).
     * Sent to parties who haven't completed their check-in steps.
     */
    CHECK_IN_REMINDER,

    /**
     * Triggered when the host completes their vehicle condition submission.
     * Sent to guest to proceed with their acknowledgment.
     */
    CHECK_IN_HOST_COMPLETE,

    /**
     * Triggered when both parties confirm and the trip officially starts.
     * Sent to both host and guest.
     */
    TRIP_STARTED,

    /**
     * Triggered when the host fails to submit check-in by trip start time.
     * Sent to guest with guidance on next steps.
     */
    NO_SHOW_HOST,

    /**
     * Triggered when the guest fails to acknowledge check-in by trip start time.
     * Sent to host with guidance on next steps.
     */
    NO_SHOW_GUEST,

    /**
     * Triggered when guest marks a hotspot/pre-existing damage.
     * Sent to host for awareness.
     */
    HOTSPOT_MARKED,

    /**
     * Triggered when handshake confirmation is complete.
     * Sent to both parties confirming successful handover.
     */
    HANDSHAKE_CONFIRMED
}
