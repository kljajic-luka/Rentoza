package org.example.rentoza.payment;

import java.util.EnumSet;
import java.util.Set;

/**
 * Strongly-typed state machine for refund lifecycle.
 *
 * <pre>
 * PENDING
 *   └─[submit to gateway]──────────────────────► PROCESSING
 *                                                     │
 *                          ┌──────────────────────────┤
 *                          │                          │
 *                     [gateway ok]             [gateway fails]
 *                          │                          │
 *                          ▼                          ▼
 *                      COMPLETED                    FAILED
 *                                                     │
 *                                           [max retries exceeded]
 *                                                     │
 *                                                     ▼
 *                                               MANUAL_REVIEW
 * </pre>
 *
 * <p><b>STALE PROCESSING recovery:</b> Any record stuck in {@code PROCESSING} for
 * longer than {@code app.payment.refund.stale-processing-minutes} is treated as
 * {@code FAILED} by the recovery scheduler and re-queued.
 */
public enum RefundLifecycleStatus {

    /** Refund calculated. Awaiting submission to gateway. */
    PENDING,

    /**
     * Submitted to payment gateway. Awaiting confirmation.
     * If stuck here >{@code stale-processing-minutes}, the scheduler will force-retry.
     */
    PROCESSING,

    /** Refund confirmed by gateway. Terminal success state. */
    COMPLETED,

    /**
     * Gateway rejected or a transient error occurred.
     * Will be retried up to {@code app.payment.refund.max-retries} times.
     */
    FAILED,

    /**
     * Max retries exhausted. Requires manual operator intervention.
     * Terminal failure state.
     */
    MANUAL_REVIEW;

    // ──────────────────────────────────────────────────────────────────────────

    private static final Set<RefundLifecycleStatus> TERMINAL_STATES = EnumSet.of(
            COMPLETED, MANUAL_REVIEW
    );

    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }

    public boolean isRecoverable() {
        return this == FAILED || this == PROCESSING;
    }

    public boolean canTransitionTo(RefundLifecycleStatus next) {
        if (this == next) return true;
        return switch (this) {
            case PENDING     -> next == PROCESSING || next == MANUAL_REVIEW;
            case PROCESSING  -> next == COMPLETED || next == FAILED || next == MANUAL_REVIEW;
            case FAILED      -> next == PROCESSING || next == MANUAL_REVIEW;
            case COMPLETED, MANUAL_REVIEW -> false;
        };
    }

    public RefundLifecycleStatus transition(RefundLifecycleStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Invalid refund lifecycle transition: " + this + " → " + next);
        }
        return next;
    }
}
