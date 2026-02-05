package org.example.rentoza.booking.dto;

import org.example.rentoza.booking.cancellation.CancelledBy;
import org.example.rentoza.booking.cancellation.CancellationReason;
import org.example.rentoza.booking.cancellation.RefundStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Result DTO returned AFTER cancellation has been executed.
 * 
 * <p>Contains the final state of the cancellation, including the
 * created {@code CancellationRecord} ID and refund status.
 * 
 * <p><b>Immutability:</b> Once returned, this represents a completed
 * (and irreversible) cancellation.
 * 
 * @param bookingId the cancelled booking's ID
 * @param cancellationRecordId the ID of the created CancellationRecord audit entry
 * @param cancelledBy who initiated the cancellation (GUEST, HOST, SYSTEM)
 * @param reason the specific reason selected for cancellation
 * @param cancelledAt timestamp when cancellation was processed
 * @param hoursBeforeTripStart hours remaining before trip would have started
 * @param originalTotalPrice the original booking total
 * @param penaltyAmount penalty charged (0 if free cancellation)
 * @param refundToGuest amount being refunded to guest
 * @param payoutToHost amount being paid out to host
 * @param refundStatus current status of the refund (PENDING, PROCESSING, etc.)
 * @param appliedRule the rule that was applied for this cancellation
 * @param hostPenaltyApplied if host cancelled, the penalty amount charged to them
 * @param hostNewTier if host cancelled, their new penalty tier (1, 2, or 3)
 * @param hostSuspendedUntil if host was suspended, when suspension ends
 * @since 2024-01 (Cancellation Policy Migration - Phase 2)
 */
public record CancellationResultDTO(
    Long bookingId,
    Long cancellationRecordId,
    CancelledBy cancelledBy,
    CancellationReason reason,
    LocalDateTime cancelledAt,
    long hoursBeforeTripStart,
    BigDecimal originalTotalPrice,
    BigDecimal penaltyAmount,
    BigDecimal refundToGuest,
    BigDecimal payoutToHost,
    RefundStatus refundStatus,
    String appliedRule,
    BigDecimal hostPenaltyApplied,
    Integer hostNewTier,
    LocalDateTime hostSuspendedUntil
) {
    /**
     * Builder pattern for easier construction in service layer.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long bookingId;
        private Long cancellationRecordId;
        private CancelledBy cancelledBy;
        private CancellationReason reason;
        private LocalDateTime cancelledAt;
        private long hoursBeforeTripStart;
        private BigDecimal originalTotalPrice;
        private BigDecimal penaltyAmount;
        private BigDecimal refundToGuest;
        private BigDecimal payoutToHost;
        private RefundStatus refundStatus;
        private String appliedRule;
        private BigDecimal hostPenaltyApplied;
        private Integer hostNewTier;
        private LocalDateTime hostSuspendedUntil;

        public Builder bookingId(Long bookingId) {
            this.bookingId = bookingId;
            return this;
        }

        public Builder cancellationRecordId(Long cancellationRecordId) {
            this.cancellationRecordId = cancellationRecordId;
            return this;
        }

        public Builder cancelledBy(CancelledBy cancelledBy) {
            this.cancelledBy = cancelledBy;
            return this;
        }

        public Builder reason(CancellationReason reason) {
            this.reason = reason;
            return this;
        }

        public Builder cancelledAt(LocalDateTime cancelledAt) {
            this.cancelledAt = cancelledAt;
            return this;
        }

        public Builder hoursBeforeTripStart(long hoursBeforeTripStart) {
            this.hoursBeforeTripStart = hoursBeforeTripStart;
            return this;
        }

        public Builder originalTotalPrice(BigDecimal originalTotalPrice) {
            this.originalTotalPrice = originalTotalPrice;
            return this;
        }

        public Builder penaltyAmount(BigDecimal penaltyAmount) {
            this.penaltyAmount = penaltyAmount;
            return this;
        }

        public Builder refundToGuest(BigDecimal refundToGuest) {
            this.refundToGuest = refundToGuest;
            return this;
        }

        public Builder payoutToHost(BigDecimal payoutToHost) {
            this.payoutToHost = payoutToHost;
            return this;
        }

        public Builder refundStatus(RefundStatus refundStatus) {
            this.refundStatus = refundStatus;
            return this;
        }

        public Builder appliedRule(String appliedRule) {
            this.appliedRule = appliedRule;
            return this;
        }

        public Builder hostPenaltyApplied(BigDecimal hostPenaltyApplied) {
            this.hostPenaltyApplied = hostPenaltyApplied;
            return this;
        }

        public Builder hostNewTier(Integer hostNewTier) {
            this.hostNewTier = hostNewTier;
            return this;
        }

        public Builder hostSuspendedUntil(LocalDateTime hostSuspendedUntil) {
            this.hostSuspendedUntil = hostSuspendedUntil;
            return this;
        }

        public CancellationResultDTO build() {
            return new CancellationResultDTO(
                bookingId,
                cancellationRecordId,
                cancelledBy,
                reason,
                cancelledAt,
                hoursBeforeTripStart,
                originalTotalPrice,
                penaltyAmount,
                refundToGuest,
                payoutToHost,
                refundStatus,
                appliedRule,
                hostPenaltyApplied,
                hostNewTier,
                hostSuspendedUntil
            );
        }
    }
}
