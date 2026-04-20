package org.example.rentoza.booking.cancellation;

/**
 * Tracks the processing status of a refund after cancellation.
 * 
 * <p>This enum represents the lifecycle of a refund transaction:
 * <pre>
 * PENDING → PROCESSING → COMPLETED
 *                ↓
 *              FAILED
 * </pre>
 * 
 * <p><b>Integration:</b> In production, this would integrate with payment
 * gateway (Stripe, PayPal) refund APIs. Current implementation is simulated.
 * 
 * @since 2024-01 (Cancellation Policy Migration - Phase 1)
 * @see CancellationRecord
 */
public enum RefundStatus {
    
    /**
     * Refund has been calculated but not yet submitted to payment gateway.
     * Initial state after cancellation processing.
     */
    PENDING,
    
    /**
     * Refund has been submitted to payment gateway and is being processed.
     * Typically takes 3-5 business days for bank transfer.
     */
    PROCESSING,
    
    /**
     * Refund has been successfully completed.
     * Funds have been returned to guest's original payment method.
     */
    COMPLETED,
    
    /**
     * Refund processing failed — eligible for retry (transient provider error).
     *
     * <p>Failure reasons may include:
     * <ul>
     *   <li>Payment method expired or invalid</li>
     *   <li>Bank rejected the refund</li>
     *   <li>Payment gateway error</li>
     * </ul>
     */
    FAILED,

    /**
     * Terminal state: refund exhausted all retry attempts and requires a human agent.
     *
     * <p>Transitions here from {@code FAILED} when
     * {@code CancellationRecord.retryCount >= CancellationRecord.maxRetries}.
     * Support team must investigate and either manually trigger a refund
     * or issue a credit note to the guest.
     */
    MANUAL_REVIEW
}
