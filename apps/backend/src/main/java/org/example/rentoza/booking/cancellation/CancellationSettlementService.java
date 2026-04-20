package org.example.rentoza.booking.cancellation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.payment.PaymentProvider.PaymentResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class CancellationSettlementService {

    private static final ZoneId BELGRADE_ZONE = ZoneId.of("Europe/Belgrade");
    private static final String DEFAULT_POLICY_VERSION = "TURO_V1.0_2024";

    private final CancellationRecordRepository cancellationRecordRepository;
    private final BookingRepository bookingRepository;
    private final BookingPaymentService bookingPaymentService;

    @Value("${app.payment.refund.retry-backoff-minutes:60}")
    private int refundRetryBackoffMinutes;

    @Transactional
    public CancellationRecord beginSettlement(Booking booking,
                                              CancelledBy cancelledBy,
                                              CancellationReason reason,
                                              String notes,
                                              BigDecimal penaltyAmount,
                                              BigDecimal refundToGuest,
                                              BigDecimal payoutToHost,
                                              String appliedRule,
                                              String policyVersion,
                                              BigDecimal dailyRateSnapshot,
                                              LocalDateTime initiatedAt) {
        CancellationRecord existing = cancellationRecordRepository.findByBookingId(booking.getId()).orElse(null);
        if (existing != null) {
            if (booking.getStatus() != BookingStatus.CANCELLED && booking.getStatus() != BookingStatus.REFUND_FAILED) {
                booking.setStatus(BookingStatus.CANCELLATION_PENDING_SETTLEMENT);
                if (booking.getCancelledAt() == null) {
                    booking.setCancelledAt(initiatedAt);
                }
                booking.setCancelledBy(cancelledBy);
                booking.setCancellationRecord(existing);
                bookingRepository.save(booking);
            }
            return existing;
        }

        BigDecimal total = booking.getTotalAmount() != null ? booking.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal dailyRate = dailyRateSnapshot != null ? dailyRateSnapshot : booking.getSnapshotDailyRate();

        CancellationRecord record = CancellationRecord.builder()
                .booking(booking)
                .cancelledBy(cancelledBy)
                .reason(reason)
                .notes(notes)
                .initiatedAt(initiatedAt)
                .processedAt(initiatedAt)
                .hoursBeforeTripStart(calculateHoursBeforeTripStart(booking, initiatedAt))
                .originalTotalPrice(total)
                .bookingTotal(total)
                .dailyRateSnapshot(dailyRate)
                .penaltyAmount(nullSafe(penaltyAmount))
                .refundToGuest(nullSafe(refundToGuest))
                .payoutToHost(nullSafe(payoutToHost))
                .refundStatus(RefundStatus.PENDING)
                .policyVersion(policyVersion != null ? policyVersion : DEFAULT_POLICY_VERSION)
                .appliedRule(appliedRule)
                .timezone(BELGRADE_ZONE.getId())
                .tripStartDate(booking.getStartDate())
                .tripEndDate(booking.getEndDate())
                .build();

        record = cancellationRecordRepository.save(record);

        booking.setStatus(BookingStatus.CANCELLATION_PENDING_SETTLEMENT);
        booking.setCancelledBy(cancelledBy);
        booking.setCancelledAt(initiatedAt);
        booking.setCancellationRecord(record);
        bookingRepository.save(booking);

        return record;
    }

    @Transactional
    public CancellationRecord beginFullRefundSettlement(Booking booking,
                                                        CancelledBy cancelledBy,
                                                        CancellationReason reason,
                                                        String notes,
                                                        String appliedRule) {
        return beginSettlement(
                booking,
                cancelledBy,
                reason,
                notes,
                BigDecimal.ZERO,
                booking.getTotalAmount() != null ? booking.getTotalAmount() : BigDecimal.ZERO,
                BigDecimal.ZERO,
                appliedRule,
                DEFAULT_POLICY_VERSION,
                booking.getSnapshotDailyRate(),
                LocalDateTime.now(BELGRADE_ZONE)
        );
    }

    @Transactional
    public SettlementAttemptResult beginAndAttemptFullRefundSettlement(Booking booking,
                                                                       CancelledBy cancelledBy,
                                                                       CancellationReason reason,
                                                                       String notes,
                                                                       String appliedRule,
                                                                       String settlementReason) {
        CancellationRecord record = beginFullRefundSettlement(booking, cancelledBy, reason, notes, appliedRule);
        return attemptSettlement(record, settlementReason);
    }

    @Transactional
    public SettlementAttemptResult attemptSettlement(CancellationRecord record, String settlementReason) {
        Booking booking = record.getBooking();

        if (record.getRefundStatus() == RefundStatus.COMPLETED) {
            return new SettlementAttemptResult(record, true);
        }
        if (record.getRefundStatus() == RefundStatus.MANUAL_REVIEW) {
            return new SettlementAttemptResult(record, false);
        }

        int attempt = record.getRetryCount() + 1;
        record.setRefundStatus(RefundStatus.PROCESSING);
        record.setLastRetryAt(Instant.now());
        record.setLastError(null);
        record.setNextRetryAt(null);
        cancellationRecordRepository.save(record);

        try {
            PaymentResult result = bookingPaymentService.processCancellationSettlement(
                    booking.getId(),
                    record.getRefundToGuest(),
                    settlementReason
            );

            if (result.isSuccess()) {
                booking.setStatus(BookingStatus.CANCELLED);
                bookingRepository.save(booking);
                record.setRefundStatus(RefundStatus.COMPLETED);
                record.setRetryCount(attempt);
                record.setLastError(null);
                record.setNextRetryAt(null);
                cancellationRecordRepository.save(record);
                return new SettlementAttemptResult(record, true);
            }

            markAttemptFailure(record, booking, attempt, result.getErrorMessage());
            return new SettlementAttemptResult(record, false);
        } catch (Exception ex) {
            markAttemptFailure(record, booking, attempt, ex.getMessage());
            return new SettlementAttemptResult(record, false);
        }
    }

    private void markAttemptFailure(CancellationRecord record, Booking booking, int attempt, String error) {
        record.setRetryCount(attempt);
        record.setLastError(error);

        if (attempt >= record.getMaxRetries()) {
            record.setRefundStatus(RefundStatus.MANUAL_REVIEW);
            record.setNextRetryAt(null);
            booking.setStatus(BookingStatus.REFUND_FAILED);
            bookingRepository.save(booking);
            log.error("[CancellationSettlement] Booking {} escalated to REFUND_FAILED after {} attempts: {}",
                    booking.getId(), attempt, error);
        } else {
            record.setRefundStatus(RefundStatus.FAILED);
            record.setNextRetryAt(Instant.now().plus(refundRetryBackoffMinutes, ChronoUnit.MINUTES));
            log.warn("[CancellationSettlement] Booking {} settlement failed on attempt {}: {}",
                    booking.getId(), attempt, error);
        }

        cancellationRecordRepository.save(record);
    }

    private long calculateHoursBeforeTripStart(Booking booking, LocalDateTime initiatedAt) {
        LocalDateTime tripStart = booking.getStartTime();
        if (tripStart == null && booking.getStartDate() != null) {
            tripStart = booking.getStartDate().atStartOfDay();
        }
        if (tripStart == null) {
            return 0;
        }
        return Duration.between(initiatedAt, tripStart).toHours();
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    public record SettlementAttemptResult(CancellationRecord record, boolean settled) {
    }
}