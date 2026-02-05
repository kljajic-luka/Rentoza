package org.example.rentoza.booking.cancellation;

/**
 * Categorizes the reason for booking cancellation.
 * 
 * <p>Reasons are grouped by initiating party ({@link CancelledBy}) and may trigger
 * different handling paths:
 * <ul>
 *   <li><b>GUEST_*</b> reasons: Standard penalty calculation applies</li>
 *   <li><b>HOST_*</b> reasons: May qualify for penalty waiver if documented</li>
 *   <li><b>SYSTEM_*</b> reasons: Automatic, no penalty to either party</li>
 * </ul>
 * 
 * <p><b>Exception Handling:</b> HOST reasons marked with "may waive" can trigger
 * an admin review workflow. If approved, the host's penalty is waived and the
 * cancellation doesn't count toward their yearly tier.
 * 
 * @since 2024-01 (Cancellation Policy Migration - Phase 1)
 * @see CancellationRecord
 * @see CancelledBy
 */
public enum CancellationReason {
    
    // ==================== GUEST REASONS ====================
    
    /**
     * Guest changed their travel plans (most common).
     * Standard penalty rules apply based on timing.
     */
    GUEST_CHANGE_OF_PLANS,
    
    /**
     * Guest found an alternative vehicle/service.
     * Standard penalty rules apply.
     */
    GUEST_FOUND_ALTERNATIVE,
    
    /**
     * Guest has a personal/medical emergency.
     * Standard penalty rules apply (no automatic waiver).
     * 
     * <p><b>Future:</b> May support documentation upload for partial refund request.
     */
    GUEST_EMERGENCY,
    
    /**
     * Guest did not show up for pickup (no-show).
     * 100% penalty applies regardless of trip duration.
     * 
     * <p>This is typically set by the system or host after the trip start
     * time has passed without guest contact.
     */
    GUEST_NO_SHOW,
    
    // ==================== HOST REASONS ====================
    
    /**
     * Vehicle is unexpectedly unavailable (mechanical issues, prior commitment).
     * Standard host penalty applies.
     */
    HOST_VEHICLE_UNAVAILABLE,
    
    /**
     * Vehicle was damaged in a previous rental.
     * 
     * <p><b>Penalty Waiver:</b> May be waived if host provides incident report
     * or insurance claim number. Requires admin approval.
     */
    HOST_VEHICLE_DAMAGE,
    
    /**
     * Host has a personal/medical emergency.
     * 
     * <p><b>Penalty Waiver:</b> May be waived with documentation.
     * Requires admin approval.
     */
    HOST_EMERGENCY,
    
    /**
     * Host has concerns about the guest (profile, communication, prior reviews).
     * 
     * <p>Standard host penalty applies unless guest clearly violated terms
     * before pickup (requires evidence submission).
     */
    HOST_GUEST_CONCERN,
    
    /**
     * Natural disaster, severe weather, or government-mandated travel restriction
     * affecting the pickup location.
     * 
     * <p><b>Penalty Waiver:</b> Automatically waived for recognized events.
     * Platform may declare a "Force Majeure" period.
     */
    HOST_FORCE_MAJEURE,
    
    // ==================== SYSTEM REASONS ====================
    
    /**
     * Guest's payment failed after multiple retry attempts.
     * No penalty to either party. Booking is automatically cancelled.
     */
    SYSTEM_PAYMENT_FAILURE,
    
    /**
     * Car listing was removed due to policy violation.
     * No penalty to either party. Guest receives full refund.
     */
    SYSTEM_POLICY_VIOLATION,
    
    /**
     * Guest or host failed identity/license verification.
     * No penalty to either party.
     */
    SYSTEM_VERIFICATION_FAILURE,
    
    /**
     * Admin manually cancelled the booking.
     * 
     * <p>Used for edge cases that don't fit other categories:
     * <ul>
     *   <li>Duplicate bookings</li>
     *   <li>Fraudulent activity</li>
     *   <li>Legal/compliance issues</li>
     * </ul>
     * 
     * <p>Admin provides notes explaining the action.
     */
    SYSTEM_ADMIN_ACTION,
    
    /**
     * Insurance verification failed or was revoked.
     * No penalty to either party.
     */
    SYSTEM_INSURANCE_FAILURE
}
