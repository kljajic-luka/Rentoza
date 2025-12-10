package org.example.rentoza.booking;

/**
 * Booking lifecycle states for the Rentoza rental workflow.
 * 
 * <h2>State Machine</h2>
 * <pre>
 * PENDING_APPROVAL ─┬─[Host Approve]─────────► ACTIVE
 *                   ├─[Host Decline]──────────► DECLINED
 *                   ├─[Auto-Expire 48h]───────► EXPIRED_SYSTEM
 *                   └─[Cancel]────────────────► CANCELLED
 *
 * ACTIVE ──────────────[T-24h Scheduler]─────► CHECK_IN_OPEN
 *                       └─[Cancel]────────────► CANCELLED
 *
 * CHECK_IN_OPEN ───┬─[Host Completes Photos]─► CHECK_IN_HOST_COMPLETE
 *                  └─[T+30m No Host Action]──► NO_SHOW_HOST
 *
 * CHECK_IN_HOST_COMPLETE ─┬─[Guest Completes]► CHECK_IN_COMPLETE
 *                         └─[T+30m No Guest]─► NO_SHOW_GUEST
 *
 * CHECK_IN_COMPLETE ──────[Both Confirm]─────► IN_TRIP
 *
 * IN_TRIP ────────────────[Trip End/Early Return]─► CHECKOUT_OPEN
 *
 * CHECKOUT_OPEN ──────────[Guest Completes]──► CHECKOUT_GUEST_COMPLETE
 *
 * CHECKOUT_GUEST_COMPLETE ─[Host Confirms]───► CHECKOUT_HOST_COMPLETE
 *
 * CHECKOUT_HOST_COMPLETE ─[Settlement Done]──► COMPLETED
 * </pre>
 *
 * @see org.example.rentoza.booking.checkin.CheckInEvent for audit trail
 */
public enum BookingStatus {
    
    // ========== APPROVAL PHASE ==========
    
    /** Guest requested booking, awaiting host approval (48h window) */
    PENDING_APPROVAL,
    
    /** Host approved, trip is scheduled (waiting for T-24h check-in window) */
    ACTIVE,
    
    /** Booking approved by host (legacy/alternative status name) */
    APPROVED,
    
    /** Booking awaiting guest checkout payment */
    PENDING_CHECKOUT,
    
    /** Host declined the booking request */
    DECLINED,
    
    /** Booking cancelled (by renter or owner, with cancellation policy applied) */
    CANCELLED,
    
    // ========== CHECK-IN PHASE (T-24h to T+30m) ==========
    
    /** 
     * Check-in window is open (T-24h before trip start).
     * Host can upload photos, odometer, fuel level.
     * Triggered by CheckInScheduler at T-24h.
     */
    CHECK_IN_OPEN,
    
    /**
     * Host has completed their check-in (photos uploaded, readings submitted).
     * Guest can now verify ID, acknowledge condition, mark hotspots.
     */
    CHECK_IN_HOST_COMPLETE,
    
    /**
     * Both host and guest have completed their check-in sections.
     * Awaiting mutual handshake confirmation to start trip.
     */
    CHECK_IN_COMPLETE,
    
    // ========== TRIP PHASE ==========
    
    /**
     * Trip is in progress (handshake completed, car is with guest).
     * Billing clock is running. Locked until trip end.
     */
    IN_TRIP,
    
    // ========== CHECKOUT PHASE ==========
    
    /**
     * Checkout window is open (trip end time reached or early return initiated).
     * Guest can upload return photos and readings.
     */
    CHECKOUT_OPEN,
    
    /**
     * Guest has completed their checkout (photos uploaded, readings submitted).
     * Host can now verify vehicle condition and confirm return.
     */
    CHECKOUT_GUEST_COMPLETE,
    
    /**
     * Host has confirmed vehicle return.
     * May include damage assessment. Awaiting final settlement.
     */
    CHECKOUT_HOST_COMPLETE,
    
    /** Trip finished successfully (checkout completed) */
    COMPLETED,
    
    // ========== NO-SHOW / FAILURE STATES ==========
    
    /**
     * Host failed to complete check-in by T+30m.
     * Guest is eligible for full refund.
     * Triggers NO_SHOW_HOST_TRIGGERED event.
     */
    NO_SHOW_HOST,
    
    /**
     * Guest failed to complete check-in by T+30m after host completed.
     * Host may receive compensation per policy.
     * Triggers NO_SHOW_GUEST_TRIGGERED event.
     */
    NO_SHOW_GUEST,
    
    // ========== EXPIRY STATES ==========
    
    /** Pending request expired (legacy - for backwards compatibility) */
    EXPIRED,
    
    /** System auto-expired due to host inactivity (48h or trip-start buffer) */
    EXPIRED_SYSTEM
}
