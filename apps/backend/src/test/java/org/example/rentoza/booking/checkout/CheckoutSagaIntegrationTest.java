package org.example.rentoza.booking.checkout;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.booking.checkin.CheckInEventType;
import org.example.rentoza.user.User;
import org.example.rentoza.booking.checkin.CheckInEventService;
import org.example.rentoza.booking.checkin.CheckInPhotoRepository;
import org.example.rentoza.booking.checkin.GuestCheckInPhotoRepository;
import org.example.rentoza.booking.checkout.saga.CheckoutSagaStep;
import org.example.rentoza.booking.checkout.saga.CheckoutSagaOrchestrator;
import org.example.rentoza.booking.checkout.saga.CheckoutSagaState;
import org.example.rentoza.booking.checkout.saga.CheckoutSagaStateRepository;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.payment.PaymentProvider;
import org.example.rentoza.booking.photo.PhotoUrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Enterprise Integration Tests for Checkout Saga Integration.
 *
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li>Saga invocation from CheckOutService</li>
 *   <li>Consistent late fee calculation (service vs saga)</li>
 *   <li>Idempotency of saga COMPLETE_BOOKING step</li>
 *   <li>Saga retry scenarios</li>
 *   <li>Data consistency (single source of truth)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Checkout Saga Integration - Enterprise Test Suite")
class CheckoutSagaIntegrationTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private CheckInEventService eventService;

    @Mock
    private CheckInPhotoRepository photoRepository;

    @Mock
    private GuestCheckInPhotoRepository guestPhotoRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private CheckoutSagaStateRepository sagaRepository;

    @Mock
    private CheckoutSagaOrchestrator checkoutSagaOrchestrator;

    @Mock
    private DamageClaimRepository damageClaimRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private BookingPaymentService bookingPaymentService;

    @Mock
    private PaymentProvider paymentProvider;

    @Mock
    private PhotoUrlService photoUrlService;

    private MeterRegistry meterRegistry;
    private CheckOutService checkOutService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        checkOutService = new CheckOutService(
            bookingRepository,
            eventService,
            photoRepository,
            guestPhotoRepository,
            notificationService,
            checkoutSagaOrchestrator,
            damageClaimRepository,
            meterRegistry,
            eventPublisher,
            photoUrlService,
            bookingPaymentService
        );
    }

    @Test
    @DisplayName("Service delegates charge calculation to saga")
    void testServiceDelegatesToSaga() {
        // Given: Booking ready for completion
        Booking booking = createTestBooking(180); // 3 hours late
        booking.setId(12345L);
        booking.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);

        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        // When: Service completes checkout
        checkOutService.completeCheckout(booking, 1L);

        // Then: Saga is invoked
        verify(checkoutSagaOrchestrator, times(1)).startSaga(eq(12345L));

        // And: Booking status moved to settlement pending before saga finalizes it
        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(bookingCaptor.capture());
        assertThat(bookingCaptor.getValue().getStatus()).isEqualTo(BookingStatus.CHECKOUT_SETTLEMENT_PENDING);
    }

    @Test
    @DisplayName("Service does NOT calculate late fee (saga is single source of truth)")
    void testServiceDoesNotCalculateLateFee() {
        // Given: Booking with late return (3 hours)
        Booking booking = createTestBooking(180);
        booking.setId(12345L);
        booking.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);

        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        // When: Service completes checkout
        checkOutService.completeCheckout(booking, 1L);

        // Then: Booking does NOT have late fee set by service
        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(bookingCaptor.capture());

        Booking savedBooking = bookingCaptor.getValue();
        assertThat(savedBooking.getLateFeeAmount())
            .describedAs("Service should NOT set late fee - saga is single source of truth")
            .isNull();
    }

    @Test
    @DisplayName("Ghost trip flow captures deposit, records audit event, and notifies both parties")
    void testGhostTripFlowCapturesDepositAndRecordsAuditEvent() {
        Booking booking = createTestBooking(0);
        booking.setId(8000L);
        booking.setStatus(BookingStatus.CHECKOUT_OPEN);
        booking.setCheckoutOpenedAt(Instant.now().minus(49, ChronoUnit.HOURS));
        booking.setDepositAuthorizationId("dep_auth_ghost");
        booking.setSecurityDeposit(BigDecimal.valueOf(30000));
        booking.setCheckoutSessionId("ghost-session");

        when(bookingRepository.findByIdWithLock(8000L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingPaymentService.captureSecurityDeposit(8000L)).thenReturn(
                PaymentProvider.PaymentResult.builder()
                        .success(true)
                        .transactionId("cap_ghost_123")
                        .amount(BigDecimal.valueOf(30000))
                        .build()
        );

        boolean processed = checkOutService.processGhostTripNoShow(8000L, 72);

        assertThat(processed).isTrue();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.NO_SHOW_GUEST);
        assertThat(booking.getCheckoutCompletedAt()).isNotNull();
        assertThat(booking.getTripEndedAt()).isNotNull();
        assertThat(booking.getSecurityDepositReleased()).isFalse();
        assertThat(booking.getSecurityDepositHoldReason()).contains("depozit naplaćen");

        verify(eventService).recordSystemEvent(
                eq(booking),
                eq("ghost-session"),
                eq(CheckInEventType.GHOST_TRIP_NO_SHOW),
                argThat((java.util.Map<String, Object> metadata) ->
                        "CAPTURED".equals(metadata.get("paymentOutcome")) &&
                                "cap_ghost_123".equals(metadata.get("paymentDetail")) &&
                                "SCHEDULER".equals(metadata.get("triggeredBy")))
        );
        verify(notificationService, times(2)).createNotification(any());
    }

    @Test
    @DisplayName("Ghost trip flow skips bookings that are no longer checkout-open")
    void testGhostTripFlowSkipsAlreadyProcessedBookings() {
        Booking booking = createTestBooking(0);
        booking.setId(8100L);
        booking.setStatus(BookingStatus.COMPLETED);
        booking.setCheckoutOpenedAt(Instant.now().minus(72, ChronoUnit.HOURS));

        when(bookingRepository.findByIdWithLock(8100L)).thenReturn(Optional.of(booking));

        boolean processed = checkOutService.processGhostTripNoShow(8100L, 72);

        assertThat(processed).isFalse();
        verifyNoInteractions(bookingPaymentService, eventService, notificationService);
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    @DisplayName("Service continues if saga invocation fails (resilient design)")
    void testServiceContinuesOnSagaFailure() {
        // Given: Booking ready for completion
        Booking booking = createTestBooking(180);
        booking.setId(12345L);
        booking.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);

        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        // And: Saga invocation throws exception
        doThrow(new RuntimeException("Database timeout"))
            .when(checkoutSagaOrchestrator).startSaga(any());

        // When: Service completes checkout — should NOT throw
        checkOutService.completeCheckout(booking, 1L);

        // Then: Checkout still committed
        verify(bookingRepository).save(any(Booking.class));

        // And: Service does not emit final completion notifications before settlement succeeds
        verify(notificationService, never()).createNotification(any());
    }

    @Test
    @DisplayName("Saga accepts settlement-pending booking")
    void testSagaAcceptsSettlementPendingStatus() {
        // Given: Booking already moved to settlement pending by service
        Booking booking = createTestBooking(180);
        booking.setId(12345L);
        booking.setStatus(BookingStatus.CHECKOUT_SETTLEMENT_PENDING);

        CheckoutSagaState saga = CheckoutSagaState.builder()
            .bookingId(12345L)
            .status(CheckoutSagaState.SagaStatus.PENDING)
            .build();

        when(bookingRepository.findById(12345L)).thenReturn(Optional.of(booking));
        when(sagaRepository.findActiveSagaForBooking(12345L)).thenReturn(Optional.empty());
        when(sagaRepository.save(any())).thenReturn(saga);

        // When: Construct real orchestrator with all 8 params
        CheckoutSagaOrchestrator orchestrator = new CheckoutSagaOrchestrator(
            sagaRepository,
            bookingRepository,
            photoRepository,
            notificationService,
            eventService,
            eventPublisher,
            bookingPaymentService,
            paymentProvider,
            meterRegistry
        );

        // Then: No exception thrown (validates precondition accepts settlement pending)
        try {
            orchestrator.startSaga(12345L);
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("must be in CHECKOUT_HOST_COMPLETE or CHECKOUT_SETTLEMENT_PENDING")) {
                throw new AssertionError("Saga should accept CHECKOUT_SETTLEMENT_PENDING status", e);
            }
            throw e;
        }
    }

    @Test
    @DisplayName("Saga compensation clears deferred release markers instead of pretending to re-hold funds")
    void testReleaseDepositCompensationClearsDeferredReleaseMarkers() {
        Booking booking = createTestBooking(0);
        booking.setId(8200L);
        booking.setStatus(BookingStatus.CHECKOUT_SETTLEMENT_PENDING);
        booking.setSecurityDepositHoldUntil(Instant.now().plus(48, ChronoUnit.HOURS));
        booking.setSecurityDepositHoldReason("48h post-checkout hold period (standard policy)");

        CheckoutSagaState saga = CheckoutSagaState.builder()
                .sagaId(UUID.randomUUID())
                .bookingId(8200L)
                .status(CheckoutSagaState.SagaStatus.RUNNING)
                .lastCompletedStep(CheckoutSagaStep.RELEASE_DEPOSIT)
                .failedAtStep(CheckoutSagaStep.COMPLETE_BOOKING)
                .releaseTransactionId("DEFERRED-48H-8200")
                .build();

        when(bookingRepository.findById(8200L)).thenReturn(Optional.of(booking));
        when(sagaRepository.save(any(CheckoutSagaState.class))).thenAnswer(inv -> inv.getArgument(0));

        CheckoutSagaOrchestrator orchestrator = new CheckoutSagaOrchestrator(
                sagaRepository,
                bookingRepository,
                photoRepository,
                notificationService,
                eventService,
                eventPublisher,
                bookingPaymentService,
                paymentProvider,
                meterRegistry
        );

        orchestrator.startCompensation(saga);

        assertThat(booking.getSecurityDepositHoldUntil()).isNull();
        assertThat(booking.getSecurityDepositHoldReason()).isNull();
        assertThat(saga.getStatus()).isEqualTo(CheckoutSagaState.SagaStatus.COMPENSATED);
        verify(paymentProvider, never()).refund(anyString(), any(BigDecimal.class), anyString(), anyString());
    }

    @Test
    @DisplayName("Saga invocation counter increments across multiple checkouts")
    void testSagaInvocationMetrics() {
        // Given: Two bookings
        Booking booking1 = createTestBooking(60);
        booking1.setId(1L);
        booking1.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);

        Booking booking2 = createTestBooking(120);
        booking2.setId(2L);
        booking2.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);

        when(bookingRepository.save(any(Booking.class)))
            .thenReturn(booking1)
            .thenReturn(booking2);

        // When: Service completes both checkouts
        checkOutService.completeCheckout(booking1, 1L);
        checkOutService.completeCheckout(booking2, 1L);

        // Then: Saga invoked twice
        verify(checkoutSagaOrchestrator, times(2)).startSaga(anyLong());
    }

    @Test
    @DisplayName("Concurrent saga invocations return existing saga (idempotent)")
    void testConcurrentSagaInvocationsAreIdempotent() {
        // Given: Booking already has active saga
        Booking booking = createTestBooking(180);
        booking.setId(12345L);
        booking.setStatus(BookingStatus.CHECKOUT_SETTLEMENT_PENDING);

        CheckoutSagaState existingSaga = CheckoutSagaState.builder()
            .sagaId(java.util.UUID.randomUUID())
            .bookingId(12345L)
            .status(CheckoutSagaState.SagaStatus.RUNNING)
            .build();

        when(sagaRepository.findActiveSagaForBooking(12345L))
            .thenReturn(Optional.of(existingSaga));

        // When: Construct real orchestrator and invoke saga again
        CheckoutSagaOrchestrator orchestrator = new CheckoutSagaOrchestrator(
            sagaRepository,
            bookingRepository,
            photoRepository,
            notificationService,
            eventService,
            eventPublisher,
            bookingPaymentService,
            paymentProvider,
            meterRegistry
        );

        CheckoutSagaState result = orchestrator.startSaga(12345L);

        // Then: Returns existing saga (does not create duplicate)
        assertThat(result.getSagaId()).isEqualTo(existingSaga.getSagaId());
        assertThat(result.getStatus()).isEqualTo(CheckoutSagaState.SagaStatus.RUNNING);

        // And: No new saga created
        verify(sagaRepository, never()).save(any(CheckoutSagaState.class));
    }

    @Test
    @DisplayName("Service handles OptimisticLockException from saga gracefully")
    void testOptimisticLockExceptionHandling() {
        // Given: Booking ready for completion
        Booking booking = createTestBooking(180);
        booking.setId(12345L);
        booking.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);

        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        // And: Saga invocation throws OptimisticLockException
        doThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
            CheckoutSagaState.class, "Version mismatch"))
            .when(checkoutSagaOrchestrator).startSaga(any());

        // When: Service completes checkout — should NOT fail
        checkOutService.completeCheckout(booking, 1L);

        // Then: Checkout still committed (resilient design)
        verify(bookingRepository).save(any(Booking.class));
    }

    // ========== acceptDamageClaim → saga progression ==========

    @Test
    @DisplayName("Guest accept damage claim clears hold flags and saga proceeds (not suspended)")
    void testAcceptDamageClaimAllowsSagaProgression() {
        // Given: Booking in CHECKOUT_DAMAGE_DISPUTE with deposit hold set by host-confirm flow
        Booking booking = createTestBooking(0);
        booking.setId(5000L);
        booking.setStatus(BookingStatus.CHECKOUT_DAMAGE_DISPUTE);
        booking.setSecurityDeposit(java.math.BigDecimal.valueOf(30000));
        booking.setDepositAuthorizationId("dep_auth_123");
        booking.setSecurityDepositReleased(false);
        booking.setSecurityDepositHoldReason("DAMAGE_CLAIM");
        booking.setSecurityDepositHoldUntil(Instant.now().plus(7, ChronoUnit.DAYS));
        booking.setDamageClaimAmount(java.math.BigDecimal.valueOf(15000));
        booking.setCheckoutSessionId("sess-123");

        // Set up damage claim linked to booking
        org.example.rentoza.booking.dispute.DamageClaim claim = 
                org.example.rentoza.booking.dispute.DamageClaim.builder()
                .id(10L)
                .booking(booking)
                .host(booking.getCar().getOwner())
                .guest(booking.getRenter())
                .description("Scratch on door")
                .claimedAmount(java.math.BigDecimal.valueOf(15000))
                .status(org.example.rentoza.booking.dispute.DamageClaimStatus.CHECKOUT_PENDING)
                .build();
        booking.setCheckoutDamageClaim(claim);

        Long guestId = booking.getRenter().getId();
        when(bookingRepository.findByIdWithRelations(5000L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(damageClaimRepository.save(any(org.example.rentoza.booking.dispute.DamageClaim.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // When: Guest accepts damage claim
        checkOutService.acceptDamageClaim(5000L, guestId);

        // Then: Claim status updated with approved amount
        assertThat(claim.getStatus())
                .isEqualTo(org.example.rentoza.booking.dispute.DamageClaimStatus.CHECKOUT_GUEST_ACCEPTED);
        assertThat(claim.getApprovedAmount())
                .isEqualByComparingTo(java.math.BigDecimal.valueOf(15000));

        // And: Deposit hold flags cleared (so saga validation won't suspend)
        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository, atLeastOnce()).save(bookingCaptor.capture());
        Booking saved = bookingCaptor.getAllValues().stream()
                .filter(b -> b.getId() != null && b.getId().equals(5000L))
                .reduce((first, second) -> second).orElse(bookingCaptor.getValue());
        assertThat(saved.getSecurityDepositHoldReason())
                .describedAs("Hold reason must be cleared so saga validation passes")
                .isNull();
        assertThat(saved.getSecurityDepositHoldUntil())
                .describedAs("Hold-until must be cleared so saga validation passes")
                .isNull();
        assertThat(saved.getSecurityDepositResolvedAt())
                .describedAs("Must NOT be pre-set — saga capture/release steps need to run")
                .isNull();

        // And: completeCheckout() was called, which invokes saga
        verify(checkoutSagaOrchestrator).startSaga(eq(5000L));
    }

    @Test
    @DisplayName("Guest accept damage claim transitions booking to settlement pending before saga")
    void testAcceptDamageClaimCompletesBooking() {
        // Given: Booking in CHECKOUT_DAMAGE_DISPUTE
        Booking booking = createTestBooking(0);
        booking.setId(6000L);
        booking.setStatus(BookingStatus.CHECKOUT_DAMAGE_DISPUTE);
        booking.setSecurityDepositReleased(false);
        booking.setSecurityDepositHoldReason("DAMAGE_CLAIM");
        booking.setSecurityDepositHoldUntil(Instant.now().plus(7, ChronoUnit.DAYS));
        booking.setCheckoutSessionId("sess-456");

        org.example.rentoza.booking.dispute.DamageClaim claim = 
                org.example.rentoza.booking.dispute.DamageClaim.builder()
                .id(11L)
                .booking(booking)
                .host(booking.getCar().getOwner())
                .guest(booking.getRenter())
                .description("Dent")
                .claimedAmount(java.math.BigDecimal.valueOf(10000))
                .status(org.example.rentoza.booking.dispute.DamageClaimStatus.CHECKOUT_PENDING)
                .build();
        booking.setCheckoutDamageClaim(claim);

        Long guestId = booking.getRenter().getId();
        when(bookingRepository.findByIdWithRelations(6000L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(damageClaimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        checkOutService.acceptDamageClaim(6000L, guestId);

        // Then: Booking status should be settlement pending (set by completeCheckout())
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CHECKOUT_SETTLEMENT_PENDING);
        assertThat(booking.getCheckoutCompletedAt()).isNotNull();
        assertThat(booking.getTripEndedAt()).isNotNull();
    }

    @Test
    @DisplayName("Guest accept damage claim is resilient to saga invocation failure")
    void testAcceptDamageClaimResilientToSagaFailure() {
        // Given: Booking in CHECKOUT_DAMAGE_DISPUTE
        Booking booking = createTestBooking(0);
        booking.setId(7000L);
        booking.setStatus(BookingStatus.CHECKOUT_DAMAGE_DISPUTE);
        booking.setSecurityDepositReleased(false);
        booking.setSecurityDepositHoldReason("DAMAGE_CLAIM");
        booking.setCheckoutSessionId("sess-789");

        org.example.rentoza.booking.dispute.DamageClaim claim = 
                org.example.rentoza.booking.dispute.DamageClaim.builder()
                .id(12L)
                .booking(booking)
                .host(booking.getCar().getOwner())
                .guest(booking.getRenter())
                .description("Scratch")
                .claimedAmount(java.math.BigDecimal.valueOf(5000))
                .status(org.example.rentoza.booking.dispute.DamageClaimStatus.CHECKOUT_PENDING)
                .build();
        booking.setCheckoutDamageClaim(claim);

        Long guestId = booking.getRenter().getId();
        when(bookingRepository.findByIdWithRelations(7000L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(damageClaimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // And: Saga invocation will fail
        doThrow(new RuntimeException("Database timeout"))
                .when(checkoutSagaOrchestrator).startSaga(7000L);

        // When: Should NOT throw (resilient design — recovery scheduler will retry)
        checkOutService.acceptDamageClaim(7000L, guestId);

        // Then: Booking remains settlement pending and claim is still accepted
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CHECKOUT_SETTLEMENT_PENDING);
        assertThat(claim.getStatus())
                .isEqualTo(org.example.rentoza.booking.dispute.DamageClaimStatus.CHECKOUT_GUEST_ACCEPTED);
        assertThat(claim.getApprovedAmount())
                .isEqualByComparingTo(java.math.BigDecimal.valueOf(5000));
    }

    // ========== Test Data Helpers ==========

    private Booking createTestBooking(int lateMinutes) {
        Booking booking = new Booking();

        Instant now = Instant.now();
        Instant scheduledReturn = now.minus(lateMinutes, ChronoUnit.MINUTES);

        booking.setScheduledReturnTime(scheduledReturn);
        booking.setTripEndedAt(now);
        booking.setTripStartedAt(now.minus(7, ChronoUnit.DAYS));

        booking.setStartOdometer(10000);
        booking.setEndOdometer(11500);

        booking.setStartFuelLevel(100);
        booking.setEndFuelLevel(50);

        booking.setCheckoutSessionId(String.valueOf(java.util.UUID.randomUUID()));

        // Set startTime/endTime — required by calculateAllowedKm() in saga mileage step
        booking.setStartTime(java.time.LocalDateTime.now().minusDays(7));
        booking.setEndTime(java.time.LocalDateTime.now());

        // Set up Car with Owner and Renter — required by isHost() and notifyCheckoutComplete()
        User owner = new User();
        owner.setId(100L);

        Car car = new Car();
        car.setOwner(owner);
        booking.setCar(car);

        User renter = new User();
        renter.setId(200L);
        booking.setRenter(renter);

        return booking;
    }
}
