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
     * Triggered before a pending booking approval deadline to remind host action.
     */
    BOOKING_APPROVAL_REMINDER,

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
     * Triggered when admin attention is needed for no-show processing.
     * Sent to admin users.
     */
    NO_SHOW_ADMIN_ALERT,

    /**
     * Triggered when guest marks a hotspot/pre-existing damage.
     * Sent to host for awareness.
     */
    HOTSPOT_MARKED,

    /**
     * Triggered when handshake confirmation is complete.
     * Sent to both parties confirming successful handover.
     */
    HANDSHAKE_CONFIRMED,

    // ==========================================================================
    // CHECK-OUT WORKFLOW NOTIFICATIONS
    // ==========================================================================

    /**
     * Triggered when the checkout window opens (trip end or early return).
     * Sent to both host and guest.
     */
    CHECKOUT_WINDOW_OPENED,

    /**
     * Triggered when the guest completes their checkout submission.
     * Sent to host to confirm vehicle return condition.
     */
    CHECKOUT_GUEST_COMPLETE,

    /**
     * Triggered when the host reports new damage at checkout.
     * Sent to guest with damage details.
     */
    CHECKOUT_DAMAGE_REPORTED,

    /**
     * Triggered when checkout is successfully completed.
     * Sent to both host and guest.
     */
    CHECKOUT_COMPLETE,

    /**
     * Triggered when a late return is detected.
     * Sent to both parties with fee information.
     */
    LATE_RETURN_DETECTED,

    /**
      * Triggered as a reminder to complete checkout.
      * Sent to guest who hasn't returned the vehicle.
      */
    CHECKOUT_REMINDER,

    // ==========================================================================
    // DISPUTE & DAMAGE CLAIM NOTIFICATIONS
    // ==========================================================================

    /**
     * Triggered when a dispute/damage claim is resolved by admin.
     * Sent to both guest (renter) and host (owner).
     */
    DISPUTE_RESOLVED,
    
    /**
     * Triggered when a dispute is escalated to admin for review (VAL-010).
     * Sent to both parties and admin notification queue.
     * @since VAL-010
     */
    DISPUTE_ESCALATED,

    // ==========================================================================
    // RENTER VERIFICATION NOTIFICATIONS
    // ==========================================================================

    /**
     * Triggered when a renter's driver license verification is approved.
     * Sent to renter confirming they can now book vehicles.
     */
    LICENSE_VERIFICATION_APPROVED,

    /**
     * Triggered when a renter's driver license verification is rejected.
     * Sent to renter with rejection reason and resubmission guidance.
     */
    LICENSE_VERIFICATION_REJECTED,

    /**
     * Triggered when a renter's driver license is expiring within 30 days.
     * Sent as a warning to update license before expiry.
     */
    LICENSE_EXPIRING_SOON,

    /**
     * Triggered when a renter's driver license has expired.
     * Sent to inform the renter they cannot book until license is updated.
     */
    LICENSE_EXPIRED,

    // ==========================================================================
    // PHASE 4I: CHECK-IN/CHECKOUT BEGUN NOTIFICATIONS
    // ==========================================================================

    /**
     * Triggered when host starts the check-in process.
     * Sent to guest to let them know the host is preparing the vehicle.
     */
    CHECK_IN_HOST_BEGUN,

    /**
     * Triggered when guest starts the check-in process.
     * Sent to host to let them know the guest is beginning vehicle inspection.
     */
    CHECK_IN_GUEST_BEGUN,

    /**
     * Triggered when guest starts the checkout process.
     * Sent to host to let them know the guest is preparing to return the vehicle.
     */
    CHECKOUT_GUEST_BEGUN,

    // ==========================================================================
    // PAYMENT LIFECYCLE NOTIFICATIONS
    // ==========================================================================

    /**
     * Triggered when booking payment is captured (T-24h before trip start).
     * Sent to guest confirming the charge.
     */
    PAYMENT_CAPTURED,

    /**
     * Triggered when security deposit is released after trip (no damage claims).
     * Sent to guest confirming return of held funds.
     */
    DEPOSIT_RELEASED,

    /**
     * Triggered when a cancellation refund is processed.
     * Sent to guest confirming the refund amount.
     */
    REFUND_PROCESSED,

    /**
     * Triggered when a payment charge fails (e.g., remainder charge exceeding deposit).
     * Sent to host/admin for manual follow-up on outstanding balance.
     */
    PAYMENT_FAILED,

    /**
     * L2: Triggered when a booking payment authorization is expiring or has expired.
     * Sent to guest advising reauthorization. Previously PAYMENT_CAPTURED was
     * incorrectly used for this scenario.
     */
    PAYMENT_AUTH_EXPIRING,

    /**
     * L2: Triggered when a security deposit authorization is expiring or has expired.
     * Sent to guest advising contact with support. Previously DEPOSIT_RELEASED was
     * incorrectly used for this scenario.
     */
    DEPOSIT_AUTH_EXPIRING
}
