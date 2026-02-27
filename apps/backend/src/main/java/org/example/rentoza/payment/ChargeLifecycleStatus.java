package org.example.rentoza.payment;

import java.util.EnumSet;
import java.util.Set;

/**
 * Strongly-typed state machine for the booking charge lifecycle.
 *
 * <pre>
 * PENDING
 *   └─[authorize()]──────────────────────────────► AUTHORIZED
 *                                                      │
 *                       ┌──────────────────────────────┤
 *                       │                              │
 *              [auth expires]                    [capture()]
 *                       │                              │
 *                       ▼                              ▼
 *              REAUTH_REQUIRED                     CAPTURED
 *                       │                              │
 *              [reauthorize()]                   [refund()]
 *                       │                              │
 *                       ▼                              ▼
 *                  AUTHORIZED                       REFUNDED
 *
 * Any terminal failure ──────────────────────────► CAPTURE_FAILED
 *                                                      │
 *                                           [max retries exceeded]
 *                                                      │
 *                                                      ▼
 *                                               MANUAL_REVIEW
 * AUTHORIZED/PENDING ──[release()]──────────────► RELEASED
 * </pre>
 *
 * <p>Transition safety is enforced by {@link #canTransitionTo(ChargeLifecycleStatus)}.
 * Use {@link #transition(ChargeLifecycleStatus)} for atomic guarded moves.
 */
public enum ChargeLifecycleStatus {

    /** Initial state — no payment action taken yet. */
    PENDING,

    /**
     * Funds held on guest's card (authorization). Not captured.
     * Expiry tracked via {@code Booking.bookingAuthExpiresAt}.
     */
    AUTHORIZED,

    /**
     * Authorization has expired. A re-authorization is required before capture.
     * Triggered by {@link org.example.rentoza.payment.PaymentLifecycleScheduler}.
     */
    REAUTH_REQUIRED,

    /** Funds captured — guest has been charged. Terminal success state. */
    CAPTURED,

    /**
     * Payment authorization (or charge) was released / voided.
     * Terminal state — funds returned to guest without a refund transaction.
     */
    RELEASED,

    /**
     * Refund issued against a captured charge.
     * May be partial or full. Terminal success state.
     */
    REFUNDED,

    /**
     * Capture attempt failed (card declined, expired, network error).
     * Scheduler will retry up to {@code app.payment.capture.max-retries} times.
     */
    CAPTURE_FAILED,

    /**
     * Release attempt failed. Requires manual operator action.
     * Terminal failure state — funds NOT returned.
     */
    RELEASE_FAILED,

    /**
     * Max retries exhausted without success.
     * Requires manual operator review and intervention.
     * Terminal state.
     */
    MANUAL_REVIEW;

    // ──────────────────────────────────────────────────────────────────────────
    // TRANSITION GUARDS
    // ──────────────────────────────────────────────────────────────────────────

    private static final Set<ChargeLifecycleStatus> TERMINAL_STATES = EnumSet.of(
            CAPTURED, RELEASED, REFUNDED, MANUAL_REVIEW
    );

    /**
     * Returns {@code true} if this status is terminal (no further transitions allowed).
     */
    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }

    /**
     * Guard: is this status a success outcome?
     */
    public boolean isSuccess() {
        return this == CAPTURED || this == RELEASED || this == REFUNDED;
    }

    /**
     * Returns {@code true} if a transition to {@code next} is a valid move.
     * Only valid forward transitions are permitted; illegal moves return false.
     */
    public boolean canTransitionTo(ChargeLifecycleStatus next) {
        if (this == next) {
            return true; // idempotent re-apply
        }
        return switch (this) {
            case PENDING        -> next == AUTHORIZED || next == CAPTURE_FAILED || next == MANUAL_REVIEW;
            case AUTHORIZED     -> next == CAPTURED || next == RELEASED || next == CAPTURE_FAILED
                                   || next == REAUTH_REQUIRED || next == RELEASE_FAILED || next == MANUAL_REVIEW;
            case REAUTH_REQUIRED -> next == AUTHORIZED || next == RELEASED
                                   || next == RELEASE_FAILED || next == MANUAL_REVIEW;
            case CAPTURED       -> next == REFUNDED || next == MANUAL_REVIEW;
            case CAPTURE_FAILED -> next == AUTHORIZED || next == CAPTURE_FAILED || next == MANUAL_REVIEW;
            case RELEASE_FAILED -> next == RELEASED || next == MANUAL_REVIEW;
            // Terminal — no forward transitions
            case RELEASED, REFUNDED, MANUAL_REVIEW -> false;
        };
    }

    /**
     * Performs a guarded transition. Throws {@link IllegalStateException} if the
     * transition is invalid.
     *
     * @param next the desired next state
     * @return {@code next} (for fluent usage)
     * @throws IllegalStateException on invalid transition
     */
    public ChargeLifecycleStatus transition(ChargeLifecycleStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Invalid charge lifecycle transition: " + this + " → " + next);
        }
        return next;
    }
}
