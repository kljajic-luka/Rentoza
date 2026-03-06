package org.example.rentoza.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.*;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin Financial Service for payout management and escrow operations.
 * 
 * <p><b>CORE RESPONSIBILITIES:</b>
 * <ul>
 *   <li>Payout queue management (hosts awaiting payment)</li>
 *   <li>Batch payout processing with atomicity</li>
 *   <li>Escrow balance tracking and reconciliation</li>
 *   <li>Payment failure handling and retry logic</li>
 * </ul>
 * 
 * <p><b>SECURITY:</b>
 * <ul>
 *   <li>All operations require ADMIN role (enforced at controller)</li>
 *   <li>Audit trail for all financial operations</li>
 *   <li>Dry-run mode for validation before execution</li>
 * </ul>
 * 
 * <p><b>RESILIENCE:</b>
 * <ul>
 *   <li>Individual payout failures don't halt batch processing</li>
 *   <li>Retry count tracking for failed payouts</li>
 *   <li>Transactional boundaries prevent partial state</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AdminFinancialService {
    
    private final BookingRepository bookingRepo;
    private final BookingPaymentService paymentService;
    private final AdminAuditService auditService;
    
    private static final int MAX_RETRY_COUNT = 3;
    private static final int PAYOUT_DELAY_DAYS = 2; // Hold funds for 2 days after trip
    
    /**
     * Get paginated payout queue (completed bookings awaiting host payment).
     * 
     * <p>Includes bookings where:
     * <ul>
     *   <li>Status = COMPLETED</li>
     *   <li>Payout not yet processed (no payment reference)</li>
     *   <li>Past holding period (2 days since completion)</li>
     * </ul>
     */
    public Page<PayoutQueueDto> getPayoutQueue(Pageable pageable) {
        log.debug("Fetching payout queue, page: {}", pageable.getPageNumber());

        Instant cutoffDate = Instant.now().minus(PAYOUT_DELAY_DAYS, ChronoUnit.DAYS);

        // H-9 FIX: Use database-level filter for correct pagination
        Page<Booking> pendingPayouts = bookingRepo.findPendingPayouts(
            BookingStatus.COMPLETED,
            cutoffDate,
            pageable
        );

        return pendingPayouts.map(this::toPayoutQueueDto);
    }
    
    /**
     * Get escrow balance summary.
     * 
     * <p>Calculates:
     * <ul>
     *   <li>Total escrow = sum of all active bookings</li>
     *   <li>Pending payouts = completed bookings awaiting host payment</li>
     *   <li>Frozen funds = disputed amounts on hold</li>
     *   <li>Available balance = total - pending - frozen</li>
     * </ul>
     */
    public EscrowBalanceDto getEscrowBalance() {
        log.debug("Calculating escrow balance");

        // H-2 FIX: Use database aggregate instead of in-memory sum
        BigDecimal totalEscrow = bookingRepo.sumTotalAmountByStatuses(
            List.of(BookingStatus.PENDING_APPROVAL, BookingStatus.APPROVED,
                    BookingStatus.ACTIVE, BookingStatus.PENDING_CHECKOUT,
                    BookingStatus.CHECK_IN_OPEN, BookingStatus.CHECK_IN_HOST_COMPLETE,
                    BookingStatus.CHECK_IN_COMPLETE, BookingStatus.IN_TRIP,
                    BookingStatus.CHECKOUT_OPEN, BookingStatus.CHECKOUT_GUEST_COMPLETE,
                    BookingStatus.CHECKOUT_HOST_COMPLETE,
                    BookingStatus.CHECKOUT_SETTLEMENT_PENDING));

        // Pending payouts (completed, awaiting host payment)
        Instant cutoffDate = Instant.now().minus(PAYOUT_DELAY_DAYS, ChronoUnit.DAYS);
        List<Booking> pendingPayouts = bookingRepo.findByStatusAndUpdatedAtBefore(
            BookingStatus.COMPLETED, cutoffDate
        );

        BigDecimal pendingAmount = pendingPayouts.stream()
            .filter(b -> b.getPaymentReference() == null)
            .map(Booking::getTotalAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // H-2 FIX: Calculate frozen funds from disputed bookings
        BigDecimal frozenFunds = bookingRepo.sumTotalAmountByStatuses(
            List.of(BookingStatus.CHECKOUT_DAMAGE_DISPUTE, BookingStatus.CHECK_IN_DISPUTE));

        return EscrowBalanceDto.builder()
            .totalEscrowBalance(totalEscrow)
            .pendingPayouts(pendingAmount)
            .availableBalance(totalEscrow.subtract(pendingAmount).subtract(frozenFunds))
            .frozenFunds(frozenFunds)
            .activeBookingsCount(0L)
            .pendingPayoutsCount((long) pendingPayouts.size())
            .currency("RSD")
            .build();
    }
    
    /**
     * Process batch payouts to hosts.
     * 
     * <p><b>ATOMICITY:</b> Each payout is a separate transaction.
     * Individual failures don't halt the batch.
     * 
     * <p><b>DRY RUN:</b> Set dryRun=true to validate without execution.
     * 
     * @param request Batch payout request with booking IDs
     * @param admin Admin user executing the batch
     * @return Result with success/failure counts
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public BatchPayoutResult processBatchPayouts(BatchPayoutRequest request, User admin) {
        log.info("Processing batch payout: {} bookings, dryRun: {}", 
                 request.getBookingIds().size(), request.getDryRun());
        
        String batchReference = UUID.randomUUID().toString();
        List<BatchPayoutResult.PayoutFailure> failures = new ArrayList<>();
        int successCount = 0;
        BigDecimal totalProcessed = BigDecimal.ZERO;
        
        for (Long bookingId : request.getBookingIds()) {
            try {
                Booking booking = bookingRepo.findByIdWithLockForPayout(bookingId)
                    .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
                
                // Validation
                if (booking.getStatus() != BookingStatus.COMPLETED) {
                    failures.add(BatchPayoutResult.PayoutFailure.builder()
                        .bookingId(bookingId)
                        .reason("Booking not completed")
                        .errorCode("INVALID_STATUS")
                        .build());
                    continue;
                }
                
                if (booking.getPaymentReference() != null) {
                    failures.add(BatchPayoutResult.PayoutFailure.builder()
                        .bookingId(bookingId)
                        .reason("Payout already processed")
                        .errorCode("DUPLICATE_PAYOUT")
                        .build());
                    continue;
                }
                
                if (request.getDryRun() == null || !request.getDryRun()) {
                    // Execute actual payout
                    paymentService.processHostPayout(booking, batchReference);
                    
                    auditService.logAction(
                        admin,
                        AdminAction.PAYOUT_PROCESSED,
                        ResourceType.BOOKING,
                        bookingId,
                        null,
                        auditService.toJson(booking),
                        "Batch payout: " + request.getNotes()
                    );
                }
                
                successCount++;
                totalProcessed = totalProcessed.add(booking.getTotalAmount());
                
            } catch (Exception e) {
                log.error("Failed to process payout for booking {}: {}", bookingId, e.getMessage(), e);
                failures.add(BatchPayoutResult.PayoutFailure.builder()
                    .bookingId(bookingId)
                    .reason(e.getMessage())
                    .errorCode("PROCESSING_ERROR")
                    .build());
            }
        }
        
        log.info("Batch payout complete: {} success, {} failures", successCount, failures.size());
        
        return BatchPayoutResult.builder()
            .totalRequested(request.getBookingIds().size())
            .successCount(successCount)
            .failureCount(failures.size())
            .totalAmountProcessed(totalProcessed)
            .failures(failures)
            .batchReference(batchReference)
            .build();
    }
    
    /**
     * Retry failed payout for a specific booking.
     * 
     * <p>Increments retry count and attempts payment again.
     * After MAX_RETRY_COUNT, marks for manual review.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void retryPayout(Long bookingId, User admin) {
        log.info("Retrying payout for booking {}", bookingId);
        
        Booking booking = bookingRepo.findByIdWithLockForPayout(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        
        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new IllegalStateException("Booking must be completed for payout");
        }

        // H-1 FIX: Enforce retry count limit
        if (booking.getPayoutRetryCount() != null && booking.getPayoutRetryCount() >= MAX_RETRY_COUNT) {
            throw new IllegalStateException(
                "Maximum retry count (" + MAX_RETRY_COUNT + ") exceeded for booking " + bookingId +
                ". Manual payout required.");
        }

        // H-1 FIX: Track retry count before attempting
        booking.setPayoutRetryCount(
            (booking.getPayoutRetryCount() != null ? booking.getPayoutRetryCount() : 0) + 1);
        booking.setLastPayoutRetryAt(java.time.Instant.now());
        bookingRepo.save(booking);

        try {
            String reference = UUID.randomUUID().toString();
            paymentService.processHostPayout(booking, reference);
            
            auditService.logAction(
                admin,
                AdminAction.PAYOUT_PROCESSED,
                ResourceType.BOOKING,
                bookingId,
                null,
                auditService.toJson(booking),
                "Manual retry"
            );
            
        } catch (Exception e) {
            log.error("Payout retry failed for booking {}: {}", bookingId, e.getMessage(), e);
            
            auditService.logAction(
                admin,
                AdminAction.PAYOUT_FAILED,
                ResourceType.BOOKING,
                bookingId,
                null,
                auditService.toJson(booking),
                "Retry failed: " + e.getMessage()
            );
            
            throw new RuntimeException("Payout retry failed: " + e.getMessage(), e);
        }
    }
    
    // ==================== HELPER METHODS ====================
    
    private PayoutQueueDto toPayoutQueueDto(Booking booking) {
        Car car = booking.getCar();
        if (car == null || car.getOwner() == null) {
            log.warn("Booking {} has null car or owner — returning placeholder payout DTO", booking.getId());
            return PayoutQueueDto.builder()
                .bookingId(booking.getId())
                .hostId(null)
                .hostName("[DATA MISSING]")
                .hostEmail("[DATA MISSING]")
                .amountCents(booking.getTotalAmount() != null
                    ? booking.getTotalAmount().multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO)
                .currency("RSD")
                .bookingCompletedAt(booking.getUpdatedAt())
                .status("ERROR_DATA_MISSING")
                .retryCount(booking.getPayoutRetryCount() != null ? booking.getPayoutRetryCount() : 0)
                .build();
        }
        User host = car.getOwner();

        return PayoutQueueDto.builder()
            .bookingId(booking.getId())
            .hostId(host.getId())
            .hostName(host.getFirstName() + " " + host.getLastName())
            .hostEmail(host.getEmail())
            .amountCents(booking.getTotalAmount().multiply(BigDecimal.valueOf(100)))
            .currency("RSD")
            .bookingCompletedAt(booking.getUpdatedAt())
            .payoutScheduledFor(booking.getUpdatedAt().plus(PAYOUT_DELAY_DAYS, ChronoUnit.DAYS))
            .status("PENDING")
            .retryCount(booking.getPayoutRetryCount() != null ? booking.getPayoutRetryCount() : 0)
            .failureReason(null)
            .paymentReference(booking.getPaymentReference())
            .build();
    }
}
