package org.example.rentoza.payment;

import java.util.EnumSet;
import java.util.Set;

/**
 * Strongly-typed state machine for the security deposit lifecycle.
 *
 * <pre>
 * PENDING
 *   └─[authorize()]──────────────────────────────► AUTHORIZED
 *                                                      │
 *                    ┌──────────────────────────────────┤
 *                    │              │                   │
 *             [no damage]    [partial damage]    [full damage]
 *                    │              │                   │
 *                    ▼              ▼                   ▼
 *                RELEASED   PARTIAL_CAPTURED        CAPTURED
 *
 * AUTHORIZED ──[auth expires]──────────────────► EXPIRED
 * Any path ────[manual]────────────────────────► MANUAL_REVIEW
 * </pre>
 */
public enum DepositLifecycleStatus {

    /** No deposit action taken yet. */
    PENDING,

    /**
     * Deposit authorization hold placed on guest's card at check-in.
     * Amount held but not charged.
     */
    AUTHORIZED,

    /**
     * No damage — deposit authorization released in full.
     * Terminal success state.
     */
    RELEASED,

    /**
     * Partial damage claim approved — part of deposit captured, remainder released.
     * Terminal success state.
     */
    PARTIAL_CAPTURED,

    /**
     * Full deposit captured (accepted damage claim or ghost-trip penalty).
     * Terminal success state.
     */
    CAPTURED,

    /**
     * Authorization expired before capture or release could occur.
     * Requires manual review.
     */
    EXPIRED,

    /**
     * Max retries exhausted or operator action required.
     * Terminal failure state.
     */
    MANUAL_REVIEW;

    // ──────────────────────────────────────────────────────────────────────────

    private static final Set<DepositLifecycleStatus> TERMINAL_STATES = EnumSet.of(
            RELEASED, PARTIAL_CAPTURED, CAPTURED, MANUAL_REVIEW
    );

    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }

    public boolean isSettled() {
        return this == RELEASED || this == PARTIAL_CAPTURED || this == CAPTURED;
    }

    public boolean canTransitionTo(DepositLifecycleStatus next) {
        if (this == next) return true;
        return switch (this) {
            case PENDING    -> next == AUTHORIZED || next == MANUAL_REVIEW;
            case AUTHORIZED -> next == RELEASED || next == CAPTURED || next == PARTIAL_CAPTURED
                               || next == EXPIRED || next == MANUAL_REVIEW;
            case EXPIRED    -> next == MANUAL_REVIEW;
            case RELEASED, PARTIAL_CAPTURED, CAPTURED, MANUAL_REVIEW -> false;
        };
    }

    public DepositLifecycleStatus transition(DepositLifecycleStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Invalid deposit lifecycle transition: " + this + " → " + next);
        }
        return next;
    }
}
