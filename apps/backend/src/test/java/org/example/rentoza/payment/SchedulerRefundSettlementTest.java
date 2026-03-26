package org.example.rentoza.payment;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.booking.cancellation.CancellationRecordRepository;
import org.example.rentoza.booking.cancellation.CancelledBy;
import org.example.rentoza.booking.cancellation.CancellationReason;
import org.example.rentoza.booking.cancellation.RefundStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.car.Car;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.payment.PaymentProvider.PaymentResult;
import org.example.rentoza.payment.PaymentProvider.PaymentStatus;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * F-CN-1 gap closure: Proves the end-to-end scheduler-driven refund settlement
 * path through {@link SchedulerItemProcessor#processRefundSafely(CancellationRecord)}.
 *
 * <p>The existing {@code BookingLifecycleAuditTest$CancellationRefundSettlement} proves
 * PENDING creation. This test class proves the second half: PENDING → PROCESSING → COMPLETED
 * (or FAILED/MANUAL_REVIEW) via the scheduler processor that runs every 15 minutes.</p>
 *
 * <h2>Settlement Path Under Test</h2>
 * <pre>
 * CancellationRecord (refundStatus = PENDING)
 *   → PaymentLifecycleScheduler (every 15 min)
 *     → SchedulerItemProcessor.processRefundSafely()
 *       → set PROCESSING, save
 *       → BookingPaymentService.processCancellationSettlement()
 *       → on success: set COMPLETED, save, notify renter
 *       → on failure: set FAILED (or MANUAL_REVIEW if exhausted), save
 * </pre>
 *
 * @see SchedulerItemProcessor#processRefundSafely(CancellationRecord)
 * @see BookingPaymentService#processCancellationSettlement(Long, BigDecimal, String)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("F-CN-1: Scheduler-Driven Refund Settlement")
class SchedulerRefundSettlementTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingPaymentService paymentService;
    @Mock private PaymentProvider paymentProvider;
    @Mock private CancellationRecordRepository cancellationRecordRepository;
    @Mock private DamageClaimRepository damageClaimRepository;
    @Mock private NotificationService notificationService;
    @Mock private PayoutLedgerRepository payoutLedgerRepository;
    @Mock private PaymentTransactionRepository txRepository;

    private SchedulerItemProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SchedulerItemProcessor(
                bookingRepository,
                paymentService,
                paymentProvider,
                cancellationRecordRepository,
                damageClaimRepository,
                notificationService,
                payoutLedgerRepository,
                txRepository,
                new SimpleMeterRegistry()
        );
        ReflectionTestUtils.setField(processor, "refundRetryBackoffMinutes", 60);
    }

    /**
     * Stub the re-fetch that processRefundSafely performs inside its REQUIRES_NEW
     * transaction. Returns the same object reference so test assertions hold.
     */
    private void stubRefetch(CancellationRecord record) {
        when(cancellationRecordRepository.findByIdWithFullDetails(record.getId()))
                .thenReturn(Optional.of(record));
    }

    /**
     * Build a minimal CancellationRecord in PENDING state with a booking that has a renter.
     */
    private CancellationRecord createPendingRecord() {
        User renter = new User();
        renter.setId(10L);
        renter.setFirstName("Test");
        renter.setLastName("Renter");

        User owner = new User();
        owner.setId(20L);

        Car car = new Car();
        car.setId(100L);
        car.setOwner(owner);

        Booking booking = new Booking();
        booking.setId(1L);
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setRenter(renter);
        booking.setCar(car);
        booking.setTotalPrice(new BigDecimal("10000.00"));

        return CancellationRecord.builder()
                .id(500L)
                .booking(booking)
                .cancelledBy(CancelledBy.GUEST)
                .reason(CancellationReason.GUEST_CHANGE_OF_PLANS)
                .refundToGuest(new BigDecimal("8000.00"))
                .penaltyAmount(new BigDecimal("2000.00"))
                .payoutToHost(new BigDecimal("2000.00"))
                .originalTotalPrice(booking.getTotalPrice())
                .bookingTotal(booking.getTotalPrice())
                .hoursBeforeTripStart(-72L)
                .initiatedAt(LocalDateTime.now())
                .processedAt(LocalDateTime.now())
                .policyVersion("TURO_V1.0_2024")
                .refundStatus(RefundStatus.PENDING)
                .retryCount(0)
                .maxRetries(3)
                .build();
    }

    // =========================================================================
    // HAPPY PATH: PENDING → PROCESSING → COMPLETED
    // =========================================================================

    @Nested
    @DisplayName("Successful settlement: PENDING → COMPLETED")
    class SuccessfulSettlement {

        @Test
        @DisplayName("processRefundSafely transitions PENDING → PROCESSING → COMPLETED on payment success")
        void pending_to_completed_on_success() {
            CancellationRecord record = createPendingRecord();

            when(paymentService.processCancellationSettlement(
                    eq(1L), eq(new BigDecimal("8000.00")), anyString()))
                    .thenReturn(PaymentResult.builder()
                            .success(true)
                            .status(PaymentStatus.SUCCESS)
                            .transactionId("txn-refund-001")
                            .amount(new BigDecimal("8000.00"))
                            .build());

            when(cancellationRecordRepository.save(any(CancellationRecord.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            stubRefetch(record);

            // Act
            processor.processRefundSafely(record);

            // Assert — record transitions to COMPLETED
            assertThat(record.getRefundStatus())
                    .as("RefundStatus must be COMPLETED after successful settlement")
                    .isEqualTo(RefundStatus.COMPLETED);

            assertThat(record.getRetryCount())
                    .as("retryCount must be incremented to 1")
                    .isEqualTo(1);

            // Verify save was called at least twice: once for PROCESSING, once for COMPLETED
            verify(cancellationRecordRepository, atLeast(2)).save(record);

            // Verify payment service was called with correct booking ID and refund amount
            verify(paymentService).processCancellationSettlement(
                    eq(1L), eq(new BigDecimal("8000.00")), anyString());
        }

        @Test
        @DisplayName("processRefundSafely sends REFUND_PROCESSED notification to renter on success")
        void sends_notification_on_success() {
            CancellationRecord record = createPendingRecord();

            when(paymentService.processCancellationSettlement(anyLong(), any(), anyString()))
                    .thenReturn(PaymentResult.builder()
                            .success(true)
                            .status(PaymentStatus.SUCCESS)
                            .build());

            when(cancellationRecordRepository.save(any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            stubRefetch(record);

            // Act
            processor.processRefundSafely(record);

            // Assert — notification sent to renter (id=10)
            verify(notificationService).createNotification(argThat(dto ->
                    dto.getRecipientId().equals(10L) &&
                    dto.getMessage().contains("8000")));
        }

        @Test
        @DisplayName("processRefundSafely sets PROCESSING status before calling payment provider")
        void transitions_through_processing_state() {
            CancellationRecord record = createPendingRecord();

            // Track the refund status at the moment each save is called.
            // ArgumentCaptor captures the object reference (not a snapshot), so we
            // must record the status value at call time using an Answer.
            java.util.List<RefundStatus> statusAtSaveTime = new java.util.ArrayList<>();

            when(cancellationRecordRepository.save(any(CancellationRecord.class)))
                    .thenAnswer(inv -> {
                        CancellationRecord saved = inv.getArgument(0);
                        statusAtSaveTime.add(saved.getRefundStatus());
                        return saved;
                    });

            when(paymentService.processCancellationSettlement(anyLong(), any(), anyString()))
                    .thenReturn(PaymentResult.builder()
                            .success(true)
                            .status(PaymentStatus.SUCCESS)
                            .build());
            stubRefetch(record);

            // Act
            processor.processRefundSafely(record);

            // Assert — at least 2 saves occurred (PROCESSING, then COMPLETED)
            assertThat(statusAtSaveTime)
                    .as("processRefundSafely must save at least twice: PROCESSING then COMPLETED")
                    .hasSizeGreaterThanOrEqualTo(2);

            // Assert — first save set PROCESSING (before calling payment provider)
            assertThat(statusAtSaveTime.get(0))
                    .as("First save must set PROCESSING before calling payment provider")
                    .isEqualTo(RefundStatus.PROCESSING);

            // Assert — last save set COMPLETED (after successful payment)
            assertThat(statusAtSaveTime.get(statusAtSaveTime.size() - 1))
                    .as("Final save must set COMPLETED after successful payment")
                    .isEqualTo(RefundStatus.COMPLETED);
        }
    }

    // =========================================================================
    // FAILURE PATH: PENDING → PROCESSING → FAILED (retryable)
    // =========================================================================

    @Nested
    @DisplayName("Retryable failure: PENDING → FAILED")
    class RetryableFailure {

        @Test
        @DisplayName("processRefundSafely sets FAILED with nextRetryAt when payment fails and retries remain")
        void failed_with_retry_on_payment_failure() {
            CancellationRecord record = createPendingRecord();
            record.setRetryCount(0);
            record.setMaxRetries(3);

            when(paymentService.processCancellationSettlement(anyLong(), any(), anyString()))
                    .thenReturn(PaymentResult.builder()
                            .success(false)
                            .errorMessage("Provider timeout")
                            .build());

            when(cancellationRecordRepository.save(any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            stubRefetch(record);

            // Act
            processor.processRefundSafely(record);

            // Assert — status is FAILED (not MANUAL_REVIEW, since retries remain)
            assertThat(record.getRefundStatus())
                    .as("RefundStatus must be FAILED when retries remain")
                    .isEqualTo(RefundStatus.FAILED);

            assertThat(record.getRetryCount())
                    .as("retryCount must be incremented")
                    .isEqualTo(1);

            assertThat(record.getNextRetryAt())
                    .as("nextRetryAt must be set for backoff scheduling")
                    .isNotNull()
                    .isAfter(Instant.now().minusSeconds(10));

            assertThat(record.getLastError())
                    .as("lastError must capture the provider error message")
                    .isEqualTo("Provider timeout");
        }

        @Test
        @DisplayName("processRefundSafely sets FAILED on payment service exception with retries remaining")
        void failed_on_exception_with_retries() {
            CancellationRecord record = createPendingRecord();
            record.setRetryCount(1);
            record.setMaxRetries(3);

            when(paymentService.processCancellationSettlement(anyLong(), any(), anyString()))
                    .thenThrow(new RuntimeException("Connection refused"));

            when(cancellationRecordRepository.save(any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            stubRefetch(record);

            // Act
            processor.processRefundSafely(record);

            // Assert
            assertThat(record.getRefundStatus()).isEqualTo(RefundStatus.FAILED);
            assertThat(record.getRetryCount()).isEqualTo(2);
            assertThat(record.getLastError()).contains("Connection refused");
            assertThat(record.getNextRetryAt()).isNotNull();
        }
    }

    // =========================================================================
    // ESCALATION: FAILED → MANUAL_REVIEW (retries exhausted)
    // =========================================================================

    @Nested
    @DisplayName("Retry exhaustion: escalate to MANUAL_REVIEW")
    class RetryExhaustion {

        @Test
        @DisplayName("processRefundSafely escalates to MANUAL_REVIEW when max retries exhausted on failure")
        void manual_review_when_retries_exhausted_failure() {
            CancellationRecord record = createPendingRecord();
            record.setRetryCount(2); // One more attempt will hit maxRetries=3
            record.setMaxRetries(3);

            when(paymentService.processCancellationSettlement(anyLong(), any(), anyString()))
                    .thenReturn(PaymentResult.builder()
                            .success(false)
                            .errorMessage("Permanent provider error")
                            .build());

            when(cancellationRecordRepository.save(any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            stubRefetch(record);

            // Act
            processor.processRefundSafely(record);

            // Assert — escalated to MANUAL_REVIEW
            assertThat(record.getRefundStatus())
                    .as("RefundStatus must be MANUAL_REVIEW when retries exhausted")
                    .isEqualTo(RefundStatus.MANUAL_REVIEW);

            assertThat(record.getRetryCount())
                    .as("retryCount must equal maxRetries")
                    .isEqualTo(3);

            assertThat(record.getNextRetryAt())
                    .as("nextRetryAt must be null for terminal MANUAL_REVIEW state")
                    .isNull();
        }

        @Test
        @DisplayName("processRefundSafely escalates to MANUAL_REVIEW when max retries exhausted on exception")
        void manual_review_when_retries_exhausted_exception() {
            CancellationRecord record = createPendingRecord();
            record.setRetryCount(2);
            record.setMaxRetries(3);

            when(paymentService.processCancellationSettlement(anyLong(), any(), anyString()))
                    .thenThrow(new RuntimeException("Fatal gateway error"));

            when(cancellationRecordRepository.save(any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            stubRefetch(record);

            // Act
            processor.processRefundSafely(record);

            // Assert
            assertThat(record.getRefundStatus()).isEqualTo(RefundStatus.MANUAL_REVIEW);
            assertThat(record.getRetryCount()).isEqualTo(3);
            assertThat(record.getNextRetryAt()).isNull();
            assertThat(record.getLastError()).contains("Fatal gateway error");
        }
    }

    // =========================================================================
    // IDEMPOTENCY: Already-settled records
    // =========================================================================

    @Nested
    @DisplayName("Idempotency edge cases")
    class Idempotency {

        @Test
        @DisplayName("processRefundSafely records lastRetryAt timestamp for audit trail")
        void records_last_retry_at() {
            CancellationRecord record = createPendingRecord();
            Instant before = Instant.now().minusSeconds(1);

            when(paymentService.processCancellationSettlement(anyLong(), any(), anyString()))
                    .thenReturn(PaymentResult.builder()
                            .success(true)
                            .status(PaymentStatus.SUCCESS)
                            .build());

            when(cancellationRecordRepository.save(any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            stubRefetch(record);

            // Act
            processor.processRefundSafely(record);

            // Assert
            assertThat(record.getLastRetryAt())
                    .as("lastRetryAt must be set for audit trail")
                    .isNotNull()
                    .isAfter(before);
        }
    }
}
