package org.example.rentoza.booking.checkout.saga;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Saga state entity for checkout workflow persistence.
 * 
 * <h2>Saga Pattern - State Persistence</h2>
 * <p>Stores the current state of a checkout saga, enabling:
 * <ul>
 *   <li>Crash recovery - Resume from last completed step</li>
 *   <li>Audit trail - Track all saga executions</li>
 *   <li>Compensation - Know which steps to rollback</li>
 * </ul>
 * 
 * <h2>State Machine</h2>
 * <pre>
 * PENDING ──► RUNNING ──► COMPLETED
 *                │
 *                ├──► COMPENSATING ──► COMPENSATED
 *                │
 *                └──► FAILED
 * </pre>
 */
@Entity
@Table(name = "checkout_saga_state", indexes = {
        @Index(name = "idx_saga_booking_id", columnList = "booking_id"),
        @Index(name = "idx_saga_status", columnList = "status"),
        @Index(name = "idx_saga_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckoutSagaState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unique saga instance identifier.
     */
    @Column(name = "saga_id", nullable = false, unique = true, length = 36)
    private UUID sagaId;

    /**
     * Booking being processed.
     */
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    /**
     * Current saga status.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SagaStatus status;

    /**
     * Current step being processed.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", length = 30)
    private CheckoutSagaStep currentStep;

    /**
     * Last successfully completed step.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_completed_step", length = 30)
    private CheckoutSagaStep lastCompletedStep;

    /**
     * Step where failure occurred (for compensation).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "failed_at_step", length = 30)
    private CheckoutSagaStep failedAtStep;

    /**
     * Error message if failed.
     */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    // ========== CALCULATED DATA ==========

    /**
     * Extra mileage calculated in CALCULATE_CHARGES step.
     */
    @Column(name = "extra_mileage_km")
    private Integer extraMileageKm;

    /**
     * Extra mileage charge amount.
     */
    @Column(name = "extra_mileage_charge", precision = 10, scale = 2)
    private BigDecimal extraMileageCharge;

    /**
     * Fuel difference (negative = less fuel returned).
     */
    @Column(name = "fuel_difference_percent")
    private Integer fuelDifferencePercent;

    /**
     * Fuel charge amount.
     */
    @Column(name = "fuel_charge", precision = 10, scale = 2)
    private BigDecimal fuelCharge;

    /**
     * Late return hours.
     */
    @Column(name = "late_hours")
    private Integer lateHours;

    /**
     * Late fee amount.
     */
    @Column(name = "late_fee", precision = 10, scale = 2)
    private BigDecimal lateFee;

    /**
     * Damage claim charge amount (approved damage from checkout dispute).
     */
    @Column(name = "damage_claim_charge", precision = 10, scale = 2)
    private BigDecimal damageClaimCharge;

    /**
     * Total charges to deduct from deposit.
     */
    @Column(name = "total_charges", precision = 10, scale = 2)
    private BigDecimal totalCharges;

    /**
     * Amount captured from deposit.
     */
    @Column(name = "captured_amount", precision = 10, scale = 2)
    private BigDecimal capturedAmount;

    /**
     * Amount released back to guest.
     */
    @Column(name = "released_amount", precision = 10, scale = 2)
    private BigDecimal releasedAmount;

    /**
     * Remainder amount that exceeded deposit and was direct-charged to guest.
     */
    @Column(name = "remainder_amount", precision = 10, scale = 2)
    private BigDecimal remainderAmount;

    // ========== PAYMENT REFERENCES ==========

    /**
     * Payment capture transaction ID.
     */
    @Column(name = "capture_transaction_id", length = 100)
    private String captureTransactionId;

    /**
     * Deposit release transaction ID.
     */
    @Column(name = "release_transaction_id", length = 100)
    private String releaseTransactionId;

    /**
     * Transaction ID for remainder direct-charge (when fees exceed deposit).
     */
    @Column(name = "remainder_transaction_id", length = 100)
    private String remainderTransactionId;

    // ========== TIMESTAMPS ==========

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // ========== RETRY TRACKING ==========

    @Column(name = "retry_count")
    private int retryCount;

    @Column(name = "last_retry_at")
    private Instant lastRetryAt;

    @Version
    @Column(name = "version")
    private Long version;

    // ========== LIFECYCLE CALLBACKS ==========

    @PrePersist
    protected void onCreate() {
        if (sagaId == null) {
            sagaId = UUID.randomUUID();
        }
        if (status == null) {
            status = SagaStatus.PENDING;
        }
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // ========== HELPER METHODS ==========

    /**
     * Check if saga can be retried.
     */
    public boolean canRetry() {
        return status == SagaStatus.FAILED && retryCount < 3;
    }

    /**
     * Check if saga is terminal (no more actions possible).
     */
    public boolean isTerminal() {
        return status == SagaStatus.COMPLETED ||
               status == SagaStatus.COMPENSATED ||
               (status == SagaStatus.FAILED && retryCount >= 3);
    }

    /**
     * Progress calculation for UI.
     */
    public int getProgressPercent() {
        if (status == SagaStatus.COMPLETED) return 100;
        if (lastCompletedStep == null) return 0;
        return (lastCompletedStep.getOrder() * 100) / CheckoutSagaStep.values().length;
    }

    // ========== SAGA STATUS ENUM ==========

    public enum SagaStatus {
        /**
         * Saga created but not started.
         */
        PENDING,

        /**
         * Saga currently executing steps.
         */
        RUNNING,

        /**
         * All steps completed successfully.
         */
        COMPLETED,

        /**
         * A step failed, compensation in progress.
         */
        COMPENSATING,

        /**
         * Compensation completed.
         */
        COMPENSATED,

        /**
         * Saga failed (may be retryable).
         */
        FAILED,
        
        /**
         * Saga suspended awaiting external resolution (VAL-010).
         * Used when damage claim blocks deposit release.
         */
        SUSPENDED
    }
}
