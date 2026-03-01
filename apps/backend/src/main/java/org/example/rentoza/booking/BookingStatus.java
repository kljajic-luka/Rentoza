package org.example.rentoza.booking;

import java.util.EnumSet;
import java.util.Set;

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
 *                  └─[T+2h No Host Action]───► NO_SHOW_HOST
 *
 * CHECK_IN_HOST_COMPLETE ─┬─[Guest Completes]► CHECK_IN_COMPLETE
 *                         └─[T+2h No Guest]──► NO_SHOW_GUEST
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
    
    // ========== CHECK-IN PHASE (T-24h to T+2h) ==========
    
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
    
    /**
     * Guest has disputed pre-existing damage during check-in.
     * Admin must review before trip can proceed.
     * 
     * <p>Resolution options:
     * <ul>
     *   <li>CANCEL_BOOKING → CANCELLED (full refund)</li>
     *   <li>PROCEED_WITH_DAMAGE_NOTED → CHECK_IN_COMPLETE (trip continues)</li>
     *   <li>DECLINE_DISPUTE → CHECK_IN_HOST_COMPLETE (guest must accept or cancel)</li>
     * </ul>
     * 
     * @since VAL-004 - Guest Check-In Dispute Flow
     */
    CHECK_IN_DISPUTE,
    
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
    
    /**
     * Host has reported new damage at checkout, deposit is held pending resolution.
     * 
     * <p><b>VAL-010:</b> Damage claim blocks deposit release.</p>
     * 
     * <p>Possible transitions:</p>
     * <ul>
     *   <li>Admin resolves in favor of host → deposit captured, COMPLETED</li>
     *   <li>Admin resolves in favor of guest → deposit released, COMPLETED</li>
     *   <li>Guest accepts damage claim → deposit captured, COMPLETED</li>
     *   <li>Timeout (7 days) → auto-escalate to admin</li>
     * </ul>
     * 
     * @since VAL-010 - Damage Claim Blocks Deposit Release
     */
    CHECKOUT_DAMAGE_DISPUTE,
    
    /** Trip finished successfully (checkout completed) */
    COMPLETED,
    
    // ========== NO-SHOW / FAILURE STATES ==========
    
    /**
     * Host failed to complete check-in by T+2h.
     * Guest is eligible for full refund.
     * Triggers NO_SHOW_HOST_TRIGGERED event.
     */
    NO_SHOW_HOST,
    
    /**
     * Guest failed to complete check-in by T+2h after host completed.
     * Host may receive compensation per policy.
     * Triggers NO_SHOW_GUEST_TRIGGERED event.
     */
    NO_SHOW_GUEST,
    
    // ========== EXPIRY STATES ==========
    
    /** Pending request expired (legacy - for backwards compatibility) */
    EXPIRED,
    
    /** System auto-expired due to host inactivity (48h or trip-start buffer) */
    EXPIRED_SYSTEM;

    // ========== SHARED STATUS SETS ==========

    /**
     * Statuses that block a car's availability for new bookings.
     *
     * <p>Used by overlap-detection queries, calendar availability, and conflict checks.
     * Any booking in one of these statuses occupies the car for the booked time range
     * and prevents new overlapping bookings from being created.
     *
     * <p>Excludes terminal states where the time range is freed:
     * CANCELLED, DECLINED, COMPLETED, EXPIRED, EXPIRED_SYSTEM, NO_SHOW_HOST, NO_SHOW_GUEST.
     */
    public static final Set<BookingStatus> BLOCKING_STATUSES = EnumSet.of(
            PENDING_APPROVAL,
            ACTIVE,
            CHECK_IN_OPEN,
            CHECK_IN_HOST_COMPLETE,
            CHECK_IN_COMPLETE,
            CHECK_IN_DISPUTE,
            IN_TRIP
    );

    // ========== HELPER METHODS ==========
    
    /**
     * Check if this status is in the check-in phase or later.
     * 
     * <p>Used for license plate visibility timing (VAL-1.2):
     * License plate should only be shown to guests once check-in window opens,
     * not when booking is merely ACTIVE (up to 48h before trip).</p>
     * 
     * <p>Statuses considered check-in phase or later:</p>
     * <ul>
     *   <li>CHECK_IN_OPEN - Window opened</li>
     *   <li>CHECK_IN_HOST_COMPLETE - Host done</li>
     *   <li>CHECK_IN_COMPLETE - Both done</li>
     *   <li>CHECK_IN_DISPUTE - Dispute in progress</li>
     *   <li>IN_TRIP - Trip active</li>
     *   <li>CHECKOUT_OPEN - Checkout started</li>
     *   <li>CHECKOUT_GUEST_COMPLETE - Guest done</li>
     *   <li>CHECKOUT_HOST_COMPLETE - Host confirmed</li>
     *   <li>CHECKOUT_DAMAGE_DISPUTE - Damage dispute</li>
     *   <li>COMPLETED - Trip finished</li>
     * </ul>
     * 
     * @return true if guest should see license plate
     * @since Issue 1.2 - License Plate Visibility Timing
     */
    public boolean isCheckInPhaseOrLater() {
        return switch (this) {
            case CHECK_IN_OPEN,
                 CHECK_IN_HOST_COMPLETE,
                 CHECK_IN_COMPLETE,
                 CHECK_IN_DISPUTE,
                 IN_TRIP,
                 CHECKOUT_OPEN,
                 CHECKOUT_GUEST_COMPLETE,
                 CHECKOUT_HOST_COMPLETE,
                 CHECKOUT_DAMAGE_DISPUTE,
                 COMPLETED -> true;
            default -> false;
        };
    }
    
    /**
     * Check if this status is a terminal state (no further transitions possible).
     * 
     * @return true if booking lifecycle is complete
     */
    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED,
                 CANCELLED,
                 DECLINED,
                 EXPIRED,
                 EXPIRED_SYSTEM,
                 NO_SHOW_HOST,
                 NO_SHOW_GUEST -> true;
            default -> false;
        };
    }
    
    /**
     * Check if this status allows cancellation by the guest.
     * 
     * @return true if guest can cancel
     */
    public boolean isCancellableByGuest() {
        return switch (this) {
            case PENDING_APPROVAL,
                 ACTIVE,
                 APPROVED -> true;
            default -> false;
        };
    }
}
