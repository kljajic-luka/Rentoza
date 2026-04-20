package org.example.rentoza.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.BatchPayoutResult;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Per-item processing for admin batch operations.
 *
 * <p>Follows the same pattern as {@code SchedulerItemProcessor}: each method runs
 * in a {@code REQUIRES_NEW} transaction so that one item failure never rolls back
 * other items in the batch. The outer coordinator ({@link AdminFinancialService})
 * is intentionally non-transactional.
 *
 * @see org.example.rentoza.payment.SchedulerItemProcessor
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminBatchItemProcessor {

    private final BookingRepository bookingRepo;
    private final BookingPaymentService paymentService;
    private final AdminAuditService auditService;

    /**
     * Process a single payout within its own transaction boundary.
     *
     * @return result for this booking — either success data or a failure entry
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchPayoutItemResult processPayoutItem(Long bookingId, String batchReference,
                                                    boolean dryRun, User admin, String notes) {
        Booking booking = bookingRepo.findByIdWithLockForPayout(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        if (booking.getStatus() != BookingStatus.COMPLETED) {
            return BatchPayoutItemResult.failure(bookingId, "Booking not completed", "INVALID_STATUS");
        }

        if (booking.getPaymentReference() != null) {
            return BatchPayoutItemResult.failure(bookingId, "Payout already processed", "DUPLICATE_PAYOUT");
        }

        if (!dryRun) {
            paymentService.processHostPayout(booking, batchReference);
            auditService.logAction(admin, AdminAction.PAYOUT_PROCESSED,
                    ResourceType.BOOKING, bookingId, null,
                    auditService.toJson(booking), "Batch payout: " + notes);
        }

        return BatchPayoutItemResult.success(bookingId, booking.getTotalAmount());
    }

    /**
     * Result of processing a single payout item.
     */
    public record BatchPayoutItemResult(
            boolean success,
            Long bookingId,
            BigDecimal amount,
            BatchPayoutResult.PayoutFailure failure
    ) {
        static BatchPayoutItemResult success(Long bookingId, BigDecimal amount) {
            return new BatchPayoutItemResult(true, bookingId, amount, null);
        }

        static BatchPayoutItemResult failure(Long bookingId, String reason, String errorCode) {
            return new BatchPayoutItemResult(false, bookingId, BigDecimal.ZERO,
                    BatchPayoutResult.PayoutFailure.builder()
                            .bookingId(bookingId)
                            .reason(reason)
                            .errorCode(errorCode)
                            .build());
        }
    }
}
