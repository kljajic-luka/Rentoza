package org.example.rentoza.booking.cancellation;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.booking.Booking;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable audit record for booking cancellations.
 * 
 * <p>Each cancelled {@link Booking} has exactly one {@code CancellationRecord} that captures:
 * <ul>
 *   <li><b>Who</b> cancelled (guest, host, system)</li>
 *   <li><b>Why</b> they cancelled (reason enum)</li>
 *   <li><b>When</b> relative to trip start (hours before)</li>
 *   <li><b>What</b> financial outcome (penalty, refund, payout)</li>
 *   <li><b>Which rules</b> were applied (policy version)</li>
 * </ul>
 * 
 * <p><b>Design Rationale:</b> This follows the Event Sourcing pattern where
 * cancellations are immutable facts. The record preserves the exact calculation
 * context for future disputes, audits, or policy version migrations.
 * 
 * <p><b>Timezone Handling:</b> All calculations use {@code timezone} field
 * (default: Europe/Belgrade) for consistent "24 hours before start" logic.
 * 
 * <p><b>Financial Precision:</b> All monetary fields use {@link BigDecimal}
 * with DECIMAL(19,2) to prevent floating-point rounding errors.
 * 
 * @since 2024-01 (Cancellation Policy Migration - Phase 1)
 * @see Booking
 * @see CancelledBy
 * @see CancellationReason
 */
@Entity
@Table(
    name = "cancellation_records",
    indexes = {
        @Index(name = "idx_cancellation_records_booking", columnList = "booking_id"),
        @Index(name = "idx_cancellation_records_initiated", columnList = "initiated_at"),
        @Index(name = "idx_cancellation_records_cancelled_by", columnList = "cancelled_by")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Optimistic locking version field.
     * Prevents lost updates when concurrent modifications occur.
     * 
     * H9 FIX: Added to prevent race conditions in cancellation updates.
     */
    @Version
    @Column(name = "version")
    private Long version;

    // ==================== RELATIONSHIP ====================

    /**
     * The booking that was cancelled.
     * One-to-one relationship with unique constraint.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    // ==================== INITIATOR & REASON ====================

    /**
     * Party that initiated the cancellation.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by", nullable = false, length = 20)
    private CancelledBy cancelledBy;

    /**
     * Categorized reason for cancellation.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 50)
    private CancellationReason reason;

    /**
     * Optional free-text notes from the user explaining cancellation.
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ==================== TIMING ====================

    /**
     * When the cancellation was requested/initiated.
     */
    @Column(name = "initiated_at", nullable = false)
    private LocalDateTime initiatedAt;

    /**
     * When the cancellation processing completed (refund calculated, etc.).
     */
    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    /**
     * Hours remaining until trip start at time of cancellation.
     * 
     * <p>Negative value indicates cancellation after trip start (no-show).
     * This is the key input for penalty calculation rules.
     */
    @Column(name = "hours_before_trip_start", nullable = false)
    private Long hoursBeforeTripStart;

    // ==================== FINANCIAL SNAPSHOT ====================

    /**
     * Original booking total price at time of cancellation.
     * Captured as snapshot for audit trail.
     */
    @Column(name = "original_total_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal originalTotalPrice;

    /**
     * Total booking amount at time of cancellation (including fees/extensions).
     * Stored separately from {@code originalTotalPrice} for audit comparisons.
     */
    @Column(name = "booking_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal bookingTotal;

    /**
     * Daily rate snapshot at time of cancellation.
     * Used for penalty calculations (1-day penalty for short trips).
     */
    @Column(name = "daily_rate_snapshot", precision = 19, scale = 2)
    private BigDecimal dailyRateSnapshot;

    /**
     * Penalty amount charged (retained by platform or host).
     * 
     * <p>For guest cancellations: Based on timing and trip duration.
     * <p>For host cancellations: Fixed tier amount (RSD 5,500 / 11,000 / 16,500).
     * <p>For system cancellations: Always zero.
     */
    @Column(name = "penalty_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal penaltyAmount;

    /**
     * Amount refunded to the guest.
     * {@code originalTotalPrice - penaltyAmount} for guest cancellations.
     */
    @Column(name = "refund_to_guest", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundToGuest;

    /**
     * Amount paid out to the host.
     * For guest cancellations with penalty, host may receive the penalty amount.
     */
    @Column(name = "payout_to_host", nullable = false, precision = 19, scale = 2)
    private BigDecimal payoutToHost;

    /**
     * Status of the refund processing.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 20)
    @Builder.Default
    private RefundStatus refundStatus = RefundStatus.PENDING;

    /**
     * Host penalty amount for host-initiated cancellations.
     * This is separate from guest penalty - it's the tier-based fee
     * charged to the host (RSD 5,500 / 11,000 / 16,500).
     */
    @Column(name = "host_penalty_amount", precision = 19, scale = 2)
    private BigDecimal hostPenaltyAmount;

    // ==================== POLICY TRACKING ====================

    /**
     * Version identifier of the cancellation policy applied.
     * Format: "YYYY-MM-VERSION" (e.g., "2024-01-TURO-V1")
     * 
     * <p>Enables auditing when policy rules change over time.
     */
    @Column(name = "policy_version", nullable = false, length = 50)
    private String policyVersion;

    /**
     * Human-readable description of the rule that was applied.
     * Example: "24H_FREE_CANCELLATION", "SHORT_TRIP_1DAY_PENALTY"
     */
    @Column(name = "applied_rule", length = 100)
    private String appliedRule;

    // ==================== TIMEZONE ====================

    /**
     * Timezone used for "24 hours before trip start" calculation.
     * Default: Europe/Belgrade (Serbian market).
     * 
     * <p>Stored for audit purposes - allows reconstruction of exact
     * calculation context if disputed.
     */
    @Column(name = "timezone", nullable = false, length = 50)
    @Builder.Default
    private String timezone = "Europe/Belgrade";

    /**
     * Trip start date (snapshot for audit).
     */
    @Column(name = "trip_start_date")
    private java.time.LocalDate tripStartDate;

    /**
     * Trip end date (snapshot for audit).
     */
    @Column(name = "trip_end_date")
    private java.time.LocalDate tripEndDate;

    // ==================== EXCEPTION HANDLING ====================

    /**
     * True if host requested penalty waiver (HOST cancellations only).
     */
    @Column(name = "penalty_waiver_requested", nullable = false)
    @Builder.Default
    private boolean penaltyWaiverRequested = false;

    /**
     * True if admin approved the penalty waiver.
     */
    @Column(name = "penalty_waiver_approved", nullable = false)
    @Builder.Default
    private boolean penaltyWaiverApproved = false;

    /**
     * URL to uploaded documentation supporting waiver request.
     * Example: Medical certificate, insurance claim, incident report.
     */
    @Column(name = "waiver_document_url", length = 500)
    private String waiverDocumentUrl;

    /**
     * Admin notes explaining waiver decision or special handling.
     */
    @Column(name = "admin_notes", columnDefinition = "TEXT")
    private String adminNotes;

    // ==================== AUDIT TIMESTAMPS ====================

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
