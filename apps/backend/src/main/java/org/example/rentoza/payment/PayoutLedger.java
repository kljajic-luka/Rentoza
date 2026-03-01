package org.example.rentoza.payment;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Marketplace payout ledger entry.
 *
 * <p>One row per host payout per booking. Tracks:
 * <ul>
 *   <li>Platform fee deduction (deterministic, HALF_UP rounding)</li>
 *   <li>Net host payout amount</li>
 *   <li>Payout lifecycle state via {@link PayoutLifecycleStatus}</li>
 *   <li>Dispute hold state — payout blocked while damage claim is open</li>
 * </ul>
 *
 * <h2>Fee Calculation</h2>
 * <pre>
 *   platformFee = tripAmount × PLATFORM_FEE_RATE (rounded HALF_UP to 2 decimal places)
 *   hostPayout  = tripAmount − platformFee
 * </pre>
 *
 * <p>Rate is captured as a snapshot at payout creation so future rate changes don't
 * alter existing payouts.
 */
@Entity
@Table(
    name = "payout_ledger",
    indexes = {
        @Index(name = "idx_pl_booking_id",      columnList = "booking_id", unique = true),
        @Index(name = "idx_pl_host_id",         columnList = "host_user_id"),
        @Index(name = "idx_pl_status",          columnList = "status"),
        @Index(name = "idx_pl_eligible_at",     columnList = "eligible_at"),
        @Index(name = "idx_pl_idempotency_key", columnList = "idempotency_key", unique = true)
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayoutLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    // ── References ────────────────────────────────────────────────────────────

    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;

    @Column(name = "host_user_id", nullable = false)
    private Long hostUserId;

    // ── Financial ─────────────────────────────────────────────────────────────

    /** Gross trip amount (total booking price). */
    @Column(name = "trip_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal tripAmount;

    /** Platform fee rate snapshot (e.g. 0.15 for 15%). */
    @Column(name = "platform_fee_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal platformFeeRate;

    /** Calculated platform fee = tripAmount × platformFeeRate (HALF_UP). */
    @Column(name = "platform_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal platformFee;

    /** PDV (Serbian VAT) on platform fee = platformFee × PDV rate (20%). */
    @Column(name = "platform_fee_pdv", precision = 19, scale = 2)
    private BigDecimal platformFeePdv;

    /** Net amount to pay host = tripAmount − platformFee. */
    @Column(name = "host_payout_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal hostPayoutAmount;

    @Column(name = "currency", length = 10, nullable = false)
    @Builder.Default
    private String currency = "RSD";

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private PayoutLifecycleStatus status = PayoutLifecycleStatus.PENDING;

    // ── Idempotency / provider ────────────────────────────────────────────────

    @Column(name = "idempotency_key", nullable = false, length = 64, unique = true)
    private String idempotencyKey;

    /** Provider-assigned payout/disbursement reference. */
    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    // ── Dispute hold ──────────────────────────────────────────────────────────

    /** Payout is on hold due to open damage claim or dispute. */
    @Column(name = "on_hold", nullable = false)
    @Builder.Default
    private boolean onHold = false;

    @Column(name = "hold_reason", length = 200)
    private String holdReason;

    // ── Payout attempt key (P0-4) ─────────────────────────────────────────────

    /**
     * Stable provider idempotency key for the current in-flight attempt.
     *
     * <p>Set before calling the provider; reused on crash-recovery replay so the
     * same provider slot is consulted rather than issuing a new key (which would
     * produce a duplicate transfer if the prior call already arrived at the bank).
     *
     * <p>Cleared after terminal resolution (COMPLETED or MANUAL_REVIEW) so the
     * next genuine retry starts a fresh attempt.
     */
    @Column(name = "current_attempt_key", length = 64)
    private String currentAttemptKey;

    // ── Retry tracking ────────────────────────────────────────────────────────

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private int maxAttempts = 3;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    // ── Dispute window ────────────────────────────────────────────────────────

    /**
     * Payout becomes eligible only after this timestamp (dispute window closure).
     * Default: trip completion + {@code app.payment.payout.dispute-hold-hours} hours.
     */
    @Column(name = "eligible_at")
    private Instant eligibleAt;

    // ── Timestamps ────────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;
}
