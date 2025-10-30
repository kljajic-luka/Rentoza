package org.example.rentoza.car;

/**
 * Cancellation policy options for car rentals
 */
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
