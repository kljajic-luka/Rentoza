package org.example.rentoza.car;

/**
 * @deprecated Legacy cancellation policy options for car rentals.
 * 
 * <p>As of 2024-01, Rentoza uses a platform-standard Turo-style cancellation
 * policy with time-based rules:
 * <ul>
 *   <li>24+ hours before trip: Free cancellation</li>
 *   <li>Less than 24h, short trip: 1 day penalty</li>
 *   <li>Less than 24h, long trip: 50% penalty</li>
 *   <li>No-show: 100% penalty</li>
 * </ul>
 * 
 * <p>This enum is retained for backward compatibility with existing car listings
 * and historical booking data. New cancellation logic is handled by
 * {@code CancellationPolicyService}.
 * 
 * @see org.example.rentoza.booking.cancellation.CancellationRecord
 */
@Deprecated(since = "2024-01", forRemoval = false)
public enum CancellationPolicy {
    /**
     * Flexible - Full refund up to 24 hours before pickup
     * Pune povraćaj novca do 24 sata pre preuzimanja
     */
    FLEXIBLE,

    /**
     * Moderate - Full refund up to 5 days before pickup
     * 50% refund 24-120 hours before
     * Pune povraćaj do 5 dana pre, 50% 24-120 sati pre
     */
    MODERATE,

    /**
     * Strict - Full refund up to 7 days before pickup
     * 50% refund 7-14 days before
     * No refund within 7 days
     * Pune povraćaj do 7 dana pre, 50% 7-14 dana pre, bez povraćaja u roku od 7 dana
     */
    STRICT,

    /**
     * Non-refundable - No refunds, best price
     * Bez povraćaja novca, najbolja cena
     */
    NON_REFUNDABLE
}
