package org.example.rentoza.booking.cancellation;

/**
 * Identifies which party initiated a booking cancellation.
 * 
 * <p>This enum is central to the Turo-style platform-standard cancellation logic,
 * determining which penalty/refund rules apply:
 * <ul>
 *   <li><b>GUEST</b> - Time-based penalties (24h rule, remorse window, trip-duration tiers)</li>
 *   <li><b>HOST</b> - Escalating penalties per calendar year (tier 1/2/3+) with suspension triggers</li>
 *   <li><b>SYSTEM</b> - No penalty to either party (payment failure, policy violation, etc.)</li>
 * </ul>
 * 
 * <p><b>Immutability:</b> Once set on a {@link CancellationRecord}, this value should never change.
 * 
 * @since 2024-01 (Cancellation Policy Migration - Phase 1)
 * @see CancellationRecord
 * @see CancellationReason
 */
public enum CancelledBy {
    
    /**
     * Guest (renter) initiated the cancellation.
     * 
     * <p>Penalty rules:
     * <ul>
     *   <li>24+ hours before trip start: FREE cancellation</li>
     *   <li>Remorse window (1 hour after booking): FREE cancellation</li>
     *   <li>Less than 24h, trip ≤ 2 days: 1 day rental rate penalty</li>
     *   <li>Less than 24h, trip &gt; 2 days: 50% penalty</li>
     *   <li>No-show (after trip start): 100% penalty</li>
     * </ul>
     */
    GUEST,
    
    /**
     * Host (car owner) initiated the cancellation.
     * 
     * <p>Penalty rules (per calendar year):
     * <ul>
     *   <li>1st offense: RSD 5,500 (~$50) + warning</li>
     *   <li>2nd offense: RSD 11,000 (~$100) + account review</li>
     *   <li>3rd+ offense: RSD 16,500 (~$150) + 7-day listing suspension</li>
     * </ul>
     * 
     * <p>Guest always receives 100% refund for host cancellations.
     */
    HOST,
    
    /**
     * System (platform) automatically cancelled the booking.
     * 
     * <p>Triggers:
     * <ul>
     *   <li>Payment failure after retry period</li>
     *   <li>Listing removal by admin (policy violation)</li>
     *   <li>Identity/insurance verification failure</li>
     *   <li>Admin action (manual system cancellation)</li>
     * </ul>
     * 
     * <p>No penalty to either party. Guest receives 100% refund.
     */
    SYSTEM
}
