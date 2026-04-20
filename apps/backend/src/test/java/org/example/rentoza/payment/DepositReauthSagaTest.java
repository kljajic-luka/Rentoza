package org.example.rentoza.payment;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.admin.service.AdminAlertService;
import org.example.rentoza.booking.checkout.saga.CheckoutSagaOrchestrator;
import org.example.rentoza.booking.checkout.saga.CheckoutSagaState;
import org.example.rentoza.booking.checkout.saga.CheckoutSagaStateRepository;
import org.example.rentoza.booking.checkin.CheckInEventService;
import org.example.rentoza.booking.checkin.CheckInPhotoRepository;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.extension.TripExtensionRepository;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.payment.PaymentProvider.PaymentResult;
import org.example.rentoza.payment.PaymentProvider.PaymentStatus;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepositReauthSagaTest {

    @Mock private CheckoutSagaStateRepository sagaRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private CheckInPhotoRepository checkInPhotoRepository;
    @Mock private NotificationService notificationService;
    @Mock private CheckInEventService eventService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private BookingPaymentService bookingPaymentService;
    @Mock private PaymentProvider paymentProvider;

    @Mock private DamageClaimRepository damageClaimRepository;
    @Mock private TripExtensionRepository tripExtensionRepository;
    @Mock private PaymentTransactionRepository txRepository;
    @Mock private PayoutLedgerRepository payoutLedgerRepository;
    @Mock private UserRepository userRepository;
    @Mock private TaxWithholdingService taxWithholdingService;

    @Mock private SchedulerIdempotencyService lockService;
    @Mock private AdminAlertService adminAlertService;

    private CheckoutSagaOrchestrator orchestrator;
    private BookingPaymentService paymentService;

    @BeforeEach
    void setUp() {
        orchestrator = new CheckoutSagaOrchestrator(
                sagaRepository,
                bookingRepository,
                checkInPhotoRepository,
                notificationService,
                eventService,
                eventPublisher,
                bookingPaymentService,
                paymentProvider,
                adminAlertService,
                userRepository,
                new SimpleMeterRegistry()
        );

        paymentService = new BookingPaymentService(
                paymentProvider,
                bookingRepository,
                damageClaimRepository,
                tripExtensionRepository,
                txRepository,
                payoutLedgerRepository,
                userRepository,
                taxWithholdingService,
                new SimpleMeterRegistry()
        );
        ReflectionTestUtils.setField(paymentService, "defaultDepositAmountRsd", 30000);
    }

    @Test
    void captureDeposit_withExpiredAuth_reauthorizesAndCaptures() {
        CheckoutSagaState saga = CheckoutSagaState.builder()
                .sagaId(UUID.randomUUID())
                .bookingId(10L)
                .totalCharges(new BigDecimal("1000.00"))
                .build();

        Booking expired = bookingWithDeposit(10L, "old-auth", Instant.now().minusSeconds(60));
        Booking refreshed = bookingWithDeposit(10L, "new-auth", Instant.now().plusSeconds(3600));

        when(bookingRepository.findById(10L)).thenReturn(Optional.of(expired), Optional.of(refreshed));
        when(bookingPaymentService.reauthorizeDeposit(10L)).thenReturn(successResult());
        when(paymentProvider.capture(eq("new-auth"), eq(new BigDecimal("1000.00")), anyString()))
                .thenReturn(PaymentProvider.ProviderResult.captureSuccess("txn-1", new BigDecimal("1000.00")));

        ReflectionTestUtils.invokeMethod(orchestrator, "executeCaptureDeposit", saga);

        verify(bookingPaymentService).reauthorizeDeposit(10L);
        verify(paymentProvider).capture(eq("new-auth"), eq(new BigDecimal("1000.00")), anyString());
        assertThat(saga.getCapturedAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void captureDeposit_withExpiredAuth_reauthFails_sagaFailsGracefully() {
        CheckoutSagaState saga = CheckoutSagaState.builder()
                .sagaId(UUID.randomUUID())
                .bookingId(11L)
                .currentStep(org.example.rentoza.booking.checkout.saga.CheckoutSagaStep.CAPTURE_DEPOSIT)
                .totalCharges(new BigDecimal("1000.00"))
                .build();

        Booking expired = bookingWithDeposit(11L, "old-auth", Instant.now().minusSeconds(60));

        when(bookingRepository.findById(11L)).thenReturn(Optional.of(expired));
        when(bookingPaymentService.reauthorizeDeposit(11L)).thenReturn(failedResult());
        when(paymentProvider.capture(eq("old-auth"), eq(new BigDecimal("1000.00")), anyString()))
                .thenReturn(PaymentProvider.ProviderResult.retryableFailure("AUTH_EXPIRED", "expired"));
        when(sagaRepository.save(any(CheckoutSagaState.class))).thenAnswer(inv -> inv.getArgument(0));

        CheckoutSagaState result = orchestrator.executeSaga(saga);

        assertThat(result.getStatus()).isEqualTo(CheckoutSagaState.SagaStatus.FAILED);
        verify(bookingPaymentService).reauthorizeDeposit(11L);
    }

    @Test
    void captureDeposit_withNonExpiredAuth_skipsReauth() {
        CheckoutSagaState saga = CheckoutSagaState.builder()
                .sagaId(UUID.randomUUID())
                .bookingId(12L)
                .totalCharges(new BigDecimal("500.00"))
                .build();

        Booking booking = bookingWithDeposit(12L, "auth-12", Instant.now().plusSeconds(3600));
        when(bookingRepository.findById(12L)).thenReturn(Optional.of(booking));
        when(paymentProvider.capture(eq("auth-12"), eq(new BigDecimal("500.00")), anyString()))
                .thenReturn(PaymentProvider.ProviderResult.captureSuccess("txn-12", new BigDecimal("500.00")));

        ReflectionTestUtils.invokeMethod(orchestrator, "executeCaptureDeposit", saga);

        verify(bookingPaymentService, never()).reauthorizeDeposit(12L);
        verify(paymentProvider).capture(eq("auth-12"), eq(new BigDecimal("500.00")), anyString());
    }

    @Test
    void reauthorizeDeposit_noStoredPaymentMethod_returnsTerminalFailure() {
        Booking booking = new Booking();
        booking.setId(20L);
        booking.setSecurityDeposit(new BigDecimal("30000.00"));
        booking.setStoredPaymentMethodId(null);

        when(bookingRepository.findByIdWithRelations(20L)).thenReturn(Optional.of(booking));

        PaymentResult result = paymentService.reauthorizeDeposit(20L);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NO_STORED_PAYMENT_METHOD");
    }

    @Test
    void escalateExpiredDisputeHolds_findsAndEscalates() {
        org.example.rentoza.booking.checkout.saga.SagaRecoveryScheduler scheduler =
                new org.example.rentoza.booking.checkout.saga.SagaRecoveryScheduler(
                        sagaRepository,
                        orchestrator,
                        lockService,
                        new SimpleMeterRegistry()
                );

        CheckoutSagaState suspended = CheckoutSagaState.builder()
                .sagaId(UUID.randomUUID())
                .bookingId(30L)
                .status(CheckoutSagaState.SagaStatus.SUSPENDED)
                .build();

        when(lockService.tryAcquireLock(eq("saga.recovery.dispute-escalation"), eq(Duration.ofHours(5))))
                .thenReturn(true);
        when(sagaRepository.findSuspendedWithExpiredHold(any(Instant.class))).thenReturn(List.of(suspended));
        when(sagaRepository.save(any(CheckoutSagaState.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.escalateExpiredDisputeHolds();

        assertThat(suspended.getStatus()).isEqualTo(CheckoutSagaState.SagaStatus.FAILED);
        verify(sagaRepository).save(suspended);
        verify(lockService).releaseLock("saga.recovery.dispute-escalation");
    }

    private Booking bookingWithDeposit(Long bookingId, String authId, Instant expiresAt) {
        Booking booking = new Booking();
        booking.setId(bookingId);
        booking.setSecurityDeposit(new BigDecimal("5000.00"));
        booking.setDepositAuthorizationId(authId);
        booking.setDepositAuthExpiresAt(expiresAt);
        booking.setStoredPaymentMethodId("pm_tok_1");
        booking.setRenter(new User());
        return booking;
    }

    private PaymentResult successResult() {
        return PaymentResult.builder()
                .success(true)
                .status(PaymentStatus.SUCCESS)
                .build();
    }

    private PaymentResult failedResult() {
        return PaymentResult.builder()
                .success(false)
                .status(PaymentStatus.FAILED)
                .errorCode("REAUTH_FAILED")
                .build();
    }
}
