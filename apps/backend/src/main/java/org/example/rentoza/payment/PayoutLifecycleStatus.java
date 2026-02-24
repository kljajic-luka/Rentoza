package org.example.rentoza.payment;

import java.util.EnumSet;
import java.util.Set;

/**
 * Strongly-typed state machine for marketplace payout lifecycle.
 *
 * <pre>
 * PENDING
 *   └─[trip confirmed + dispute window elapsed]──► ELIGIBLE
 *                                                      │
 *                                              [payout initiated]
 *                                                      │
 *                                                      ▼
 *                                                 PROCESSING
 *                                                      │
 *                          ┌──────────────────────────────────────┐
 *                          │              │                        │
 *                    [gateway ok]   [dispute raised]         [gateway fails]
 *                          │              │                        │
 *                          ▼              ▼                        ▼
 *                      COMPLETED       ON_HOLD                   FAILED
 *                                         │                        │
 *                                [dispute resolved]         [max retries]
 *                                         │                        │
 *                                         ▼                        ▼
 *                                    PROCESSING               MANUAL_REVIEW
 * </pre>
 */
public enum PayoutLifecycleStatus {

    /** Payout pending — trip must complete first. */
    PENDING,

    /**
     * Trip completed, dispute window elapsed, payout is eligible for disbursement.
     * Scheduler picks up ELIGIBLE records.
     */
    ELIGIBLE,

    /** Payout submitted to gateway. Awaiting confirmation. */
    PROCESSING,

    /** Payout confirmed. Funds transferred to host. Terminal success. */
    COMPLETED,

    /**
     * Payout held due to active dispute or damage claim.
     * Will be released once dispute is resolved.
     */
    ON_HOLD,

    /** Gateway failure. Retry eligible. */
    FAILED,

    /** Max retries exhausted. Operator intervention required. Terminal failure. */
    MANUAL_REVIEW;

    // ──────────────────────────────────────────────────────────────────────────

    private static final Set<PayoutLifecycleStatus> TERMINAL_STATES = EnumSet.of(
            COMPLETED, MANUAL_REVIEW
    );

    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }

    public boolean canTransitionTo(PayoutLifecycleStatus next) {
        if (this == next) return true;
        return switch (this) {
            case PENDING     -> next == ELIGIBLE || next == MANUAL_REVIEW;
            case ELIGIBLE    -> next == PROCESSING || next == ON_HOLD || next == MANUAL_REVIEW;
            case PROCESSING  -> next == COMPLETED || next == FAILED || next == ON_HOLD || next == MANUAL_REVIEW;
            case ON_HOLD     -> next == PROCESSING || next == MANUAL_REVIEW;
            case FAILED      -> next == PROCESSING || next == MANUAL_REVIEW;
            case COMPLETED, MANUAL_REVIEW -> false;
        };
    }

    public PayoutLifecycleStatus transition(PayoutLifecycleStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Invalid payout lifecycle transition: " + this + " → " + next);
        }
        return next;
    }
}
