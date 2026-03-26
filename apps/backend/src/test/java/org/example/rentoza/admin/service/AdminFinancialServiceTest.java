package org.example.rentoza.admin.service;

import org.example.rentoza.admin.dto.BatchPayoutRequest;
import org.example.rentoza.admin.dto.BatchPayoutResult;
import org.example.rentoza.admin.dto.EscrowBalanceDto;
import org.example.rentoza.admin.entity.AdminAction;
import org.example.rentoza.admin.entity.ResourceType;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.admin.service.AdminBatchItemProcessor;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AdminFinancialService.
 *
 * Tests batch payout processing, retry logic, and escrow balance calculation:
 * - Batch payout happy path, validation failures, and duplicate guards
 * - Retry count enforcement (MAX_RETRY_COUNT = 3)
 * - Escrow balance aggregation from repository
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminFinancialService Tests")
class AdminFinancialServiceTest {

    @Mock
    private BookingRepository bookingRepo;

    @Mock
    private BookingPaymentService paymentService;

    @Mock
    private AdminAuditService auditService;

    @Mock
    private AdminBatchItemProcessor batchItemProcessor;

    private AdminFinancialService adminFinancialService;

    @Captor
    private ArgumentCaptor<AdminAction> actionCaptor;

    private User testAdmin;

    @BeforeEach
    void setUp() {
        adminFinancialService = new AdminFinancialService(bookingRepo, paymentService, auditService, batchItemProcessor);

        testAdmin = new User();
        testAdmin.setId(99L);
        testAdmin.setEmail("admin@test.com");
        testAdmin.setFirstName("Admin");
        testAdmin.setLastName("User");
    }

    /**
     * Creates a standard completed booking for payout tests.
     */
    private Booking createCompletedBooking() {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setStatus(BookingStatus.COMPLETED);
        booking.setTotalPrice(BigDecimal.valueOf(5000));

        Car car = new Car();
        car.setId(10L);

        User host = new User();
        host.setId(20L);
        host.setFirstName("Host");
        host.setLastName("User");
        host.setEmail("host@test.com");
        car.setOwner(host);

        booking.setCar(car);
        return booking;
    }

    // ==================== processBatchPayouts() ====================

    @Nested
    @DisplayName("processBatchPayouts()")
    class ProcessBatchPayoutsTests {

        @Test
        @DisplayName("Should process payout for completed booking via batchItemProcessor")
        void shouldProcessPayoutSuccessfully() {
            when(batchItemProcessor.processPayoutItem(eq(1L), anyString(), eq(false), eq(testAdmin), eq("Monthly payout")))
                    .thenReturn(AdminBatchItemProcessor.BatchPayoutItemResult.success(1L, BigDecimal.valueOf(5000)));

            BatchPayoutRequest request = BatchPayoutRequest.builder()
                    .bookingIds(List.of(1L))
                    .dryRun(false)
                    .notes("Monthly payout")
                    .build();

            BatchPayoutResult result = adminFinancialService.processBatchPayouts(request, testAdmin);

            assertThat(result.getSuccessCount()).isEqualTo(1);
            assertThat(result.getFailureCount()).isEqualTo(0);
            assertThat(result.getFailures()).isEmpty();
            assertThat(result.getTotalAmountProcessed()).isEqualByComparingTo(BigDecimal.valueOf(5000));
            assertThat(result.getBatchReference()).isNotBlank();
            assertThat(result.getTotalRequested()).isEqualTo(1);

            verify(batchItemProcessor).processPayoutItem(eq(1L), anyString(), eq(false), eq(testAdmin), eq("Monthly payout"));
        }

        @Test
        @DisplayName("Should record failure when batchItemProcessor throws (e.g. booking not found)")
        void shouldRecordFailureWhenBookingNotFound() {
            when(batchItemProcessor.processPayoutItem(eq(1L), anyString(), eq(false), eq(testAdmin), eq("Batch run")))
                    .thenThrow(new org.example.rentoza.exception.ResourceNotFoundException("Booking not found: 1"));

            BatchPayoutRequest request = BatchPayoutRequest.builder()
                    .bookingIds(List.of(1L))
                    .dryRun(false)
                    .notes("Batch run")
                    .build();

            BatchPayoutResult result = adminFinancialService.processBatchPayouts(request, testAdmin);

            assertThat(result.getSuccessCount()).isEqualTo(0);
            assertThat(result.getFailureCount()).isEqualTo(1);
            assertThat(result.getFailures()).hasSize(1);

            BatchPayoutResult.PayoutFailure failure = result.getFailures().get(0);
            assertThat(failure.getBookingId()).isEqualTo(1L);
            assertThat(failure.getErrorCode()).isEqualTo("PROCESSING_ERROR");
            assertThat(failure.getReason()).contains("Booking not found");
        }

        @Test
        @DisplayName("Should record failure when batchItemProcessor returns INVALID_STATUS")
        void shouldRecordFailureWhenBookingNotCompleted() {
            when(batchItemProcessor.processPayoutItem(eq(1L), anyString(), eq(false), eq(testAdmin), eq("Batch run")))
                    .thenReturn(AdminBatchItemProcessor.BatchPayoutItemResult.failure(1L, "Booking not completed", "INVALID_STATUS"));

            BatchPayoutRequest request = BatchPayoutRequest.builder()
                    .bookingIds(List.of(1L))
                    .dryRun(false)
                    .notes("Batch run")
                    .build();

            BatchPayoutResult result = adminFinancialService.processBatchPayouts(request, testAdmin);

            assertThat(result.getSuccessCount()).isEqualTo(0);
            assertThat(result.getFailureCount()).isEqualTo(1);

            BatchPayoutResult.PayoutFailure failure = result.getFailures().get(0);
            assertThat(failure.getBookingId()).isEqualTo(1L);
            assertThat(failure.getReason()).isEqualTo("Booking not completed");
            assertThat(failure.getErrorCode()).isEqualTo("INVALID_STATUS");
        }

        @Test
        @DisplayName("Should record failure with DUPLICATE_PAYOUT when batchItemProcessor returns it")
        void shouldRecordFailureWhenPaymentReferenceAlreadySet() {
            when(batchItemProcessor.processPayoutItem(eq(1L), anyString(), eq(false), eq(testAdmin), eq("Batch run")))
                    .thenReturn(AdminBatchItemProcessor.BatchPayoutItemResult.failure(1L, "Payout already processed", "DUPLICATE_PAYOUT"));

            BatchPayoutRequest request = BatchPayoutRequest.builder()
                    .bookingIds(List.of(1L))
                    .dryRun(false)
                    .notes("Batch run")
                    .build();

            BatchPayoutResult result = adminFinancialService.processBatchPayouts(request, testAdmin);

            assertThat(result.getSuccessCount()).isEqualTo(0);
            assertThat(result.getFailureCount()).isEqualTo(1);

            BatchPayoutResult.PayoutFailure failure = result.getFailures().get(0);
            assertThat(failure.getBookingId()).isEqualTo(1L);
            assertThat(failure.getReason()).isEqualTo("Payout already processed");
            assertThat(failure.getErrorCode()).isEqualTo("DUPLICATE_PAYOUT");
        }
    }

    // ==================== retryPayout() ====================

    @Nested
    @DisplayName("retryPayout()")
    class RetryPayoutTests {

        @Test
        @DisplayName("Should retry payout for completed booking with retryCount below max")
        void shouldRetryPayoutSuccessfully() {
            Booking booking = createCompletedBooking();
            booking.setPayoutRetryCount(0);

            when(bookingRepo.findByIdWithLockForPayout(1L)).thenReturn(Optional.of(booking));
            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

            adminFinancialService.retryPayout(1L, testAdmin);

            assertThat(booking.getPayoutRetryCount()).isEqualTo(1);
            assertThat(booking.getLastPayoutRetryAt()).isNotNull();

            verify(paymentService).processHostPayout(eq(booking), anyString());
            verify(auditService).logAction(
                    eq(testAdmin),
                    actionCaptor.capture(),
                    eq(ResourceType.BOOKING),
                    eq(1L),
                    any(),
                    any(),
                    eq("Manual retry")
            );
            assertThat(actionCaptor.getValue()).isEqualTo(AdminAction.PAYOUT_PROCESSED);
        }

        @Test
        @DisplayName("Should throw IllegalStateException when max retries exceeded")
        void shouldThrowWhenMaxRetriesExceeded() {
            Booking booking = createCompletedBooking();
            booking.setPayoutRetryCount(3); // MAX_RETRY_COUNT = 3

            when(bookingRepo.findByIdWithLockForPayout(1L)).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> adminFinancialService.retryPayout(1L, testAdmin))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Maximum retry count")
                    .hasMessageContaining("3");

            verify(paymentService, never()).processHostPayout(any(), any());
        }

        @Test
        @DisplayName("Should log PAYOUT_FAILED audit action when payment processing throws")
        void shouldLogPayoutFailedWhenPaymentThrows() {
            Booking booking = createCompletedBooking();
            booking.setPayoutRetryCount(0);

            when(bookingRepo.findByIdWithLockForPayout(1L)).thenReturn(Optional.of(booking));
            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new RuntimeException("Payment gateway timeout"))
                    .when(paymentService).processHostPayout(any(Booking.class), anyString());

            assertThatThrownBy(() -> adminFinancialService.retryPayout(1L, testAdmin))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Payout retry failed");

            verify(auditService).logAction(
                    eq(testAdmin),
                    actionCaptor.capture(),
                    eq(ResourceType.BOOKING),
                    eq(1L),
                    any(),
                    any(),
                    any(String.class)
            );
            assertThat(actionCaptor.getValue()).isEqualTo(AdminAction.PAYOUT_FAILED);
        }
    }

    // ==================== getEscrowBalance() ====================

    @Nested
    @DisplayName("getEscrowBalance()")
    class GetEscrowBalanceTests {

        /** The exact active-phase statuses passed by AdminFinancialService.getEscrowBalance() */
        private final List<BookingStatus> ACTIVE_PHASE_STATUSES = List.of(
                BookingStatus.PENDING_APPROVAL, BookingStatus.APPROVED,
                BookingStatus.ACTIVE, BookingStatus.PENDING_CHECKOUT,
                BookingStatus.CHECK_IN_OPEN, BookingStatus.CHECK_IN_HOST_COMPLETE,
                BookingStatus.CHECK_IN_COMPLETE, BookingStatus.IN_TRIP,
                BookingStatus.CHECKOUT_OPEN, BookingStatus.CHECKOUT_GUEST_COMPLETE,
                BookingStatus.CHECKOUT_HOST_COMPLETE, BookingStatus.CHECKOUT_SETTLEMENT_PENDING);

        /** The exact dispute statuses passed by AdminFinancialService.getEscrowBalance() */
        private final List<BookingStatus> DISPUTE_STATUSES = List.of(
                BookingStatus.CHECKOUT_DAMAGE_DISPUTE, BookingStatus.CHECK_IN_DISPUTE);

        @Test
        @DisplayName("Should calculate escrow balance from repository aggregates")
        void shouldCalculateEscrowBalanceCorrectly() {
            BigDecimal totalEscrow = BigDecimal.valueOf(100000);
            when(bookingRepo.sumTotalAmountByStatuses(ACTIVE_PHASE_STATUSES))
                    .thenReturn(totalEscrow);

            BigDecimal frozenFunds = BigDecimal.valueOf(15000);
            when(bookingRepo.sumTotalAmountByStatuses(DISPUTE_STATUSES))
                    .thenReturn(frozenFunds);

            when(bookingRepo.findByStatusAndUpdatedAtBefore(
                    eq(BookingStatus.COMPLETED), any(Instant.class)))
                    .thenReturn(Collections.emptyList());

            EscrowBalanceDto dto = adminFinancialService.getEscrowBalance();

            assertThat(dto.getTotalEscrowBalance()).isEqualByComparingTo(totalEscrow);
            assertThat(dto.getFrozenFunds()).isEqualByComparingTo(frozenFunds);
            assertThat(dto.getPendingPayouts()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(dto.getAvailableBalance())
                    .isEqualByComparingTo(totalEscrow.subtract(BigDecimal.ZERO).subtract(frozenFunds));
            assertThat(dto.getCurrency()).isEqualTo("RSD");
        }

        @Test
        @DisplayName("Should include pending payout amounts from completed bookings without payment reference")
        void shouldIncludePendingPayoutAmounts() {
            BigDecimal totalEscrow = BigDecimal.valueOf(50000);
            when(bookingRepo.sumTotalAmountByStatuses(ACTIVE_PHASE_STATUSES))
                    .thenReturn(totalEscrow);

            BigDecimal frozenFunds = BigDecimal.ZERO;
            when(bookingRepo.sumTotalAmountByStatuses(DISPUTE_STATUSES))
                    .thenReturn(frozenFunds);

            // One completed booking without payment reference (pending payout)
            Booking pendingPayout = createCompletedBooking();
            pendingPayout.setPaymentReference(null);

            // One completed booking with payment reference (already paid)
            Booking alreadyPaid = createCompletedBooking();
            alreadyPaid.setId(2L);
            alreadyPaid.setPaymentReference("paid-ref-456");

            when(bookingRepo.findByStatusAndUpdatedAtBefore(
                    eq(BookingStatus.COMPLETED), any(Instant.class)))
                    .thenReturn(List.of(pendingPayout, alreadyPaid));

            EscrowBalanceDto dto = adminFinancialService.getEscrowBalance();

            // Only the booking without paymentReference counts as pending
            assertThat(dto.getPendingPayouts()).isEqualByComparingTo(BigDecimal.valueOf(5000));
            assertThat(dto.getPendingPayoutsCount()).isEqualTo(2L);
            assertThat(dto.getAvailableBalance())
                    .isEqualByComparingTo(
                            totalEscrow
                                    .subtract(BigDecimal.valueOf(5000))
                                    .subtract(frozenFunds));
        }
    }
}
