package org.example.rentoza.booking.dispute;

/**
 * Stage in booking lifecycle when a dispute was raised.
 * 
 * <p>Determines available resolution options and notification flow.
 * 
 * @since VAL-004 - Guest Check-In Dispute Flow
 */
public enum DisputeStage {
    
    /**
     * Dispute raised during check-in (before trip starts).
     * Guest reports undisclosed pre-existing damage.
     * 
     * <p>Resolution options:
     * <ul>
     *   <li>CANCEL_BOOKING - Full refund, booking cancelled</li>
     *   <li>PROCEED_WITH_DAMAGE_NOTED - Document damage, waive liability, continue trip</li>
     *   <li>DECLINE_DISPUTE - Guest must accept or self-cancel</li>
     * </ul>
     */
    CHECK_IN,
    
    /**
     * Dispute raised during checkout (after trip ends).
     * Host reports damage that occurred during rental.
     * 
     * <p>Resolution options:
     * <ul>
     *   <li>ADMIN_APPROVED - Guest pays for damage</li>
     *   <li>ADMIN_REJECTED - No damage compensation</li>
     *   <li>PARTIAL_APPROVAL - Reduced damage amount</li>
     * </ul>
     */
    CHECKOUT
}
