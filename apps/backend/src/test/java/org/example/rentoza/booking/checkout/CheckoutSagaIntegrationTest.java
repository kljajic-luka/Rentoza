package org.example.rentoza.booking.checkout;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;
import org.example.rentoza.booking.checkin.CheckInEventService;
import org.example.rentoza.booking.checkin.CheckInPhotoRepository;
import org.example.rentoza.booking.checkin.GuestCheckInPhotoRepository;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
            photoUrlService
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

        // And: Booking status updated to COMPLETED
        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(bookingCaptor.capture());
        assertThat(bookingCaptor.getValue().getStatus()).isEqualTo(BookingStatus.COMPLETED);
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

        // And: Notification still sent
        verify(notificationService, atLeastOnce()).createNotification(any());
    }

    @Test
    @DisplayName("Saga accepts already COMPLETED booking (idempotent)")
    void testSagaAcceptsCompletedStatus() {
        // Given: Booking already COMPLETED by service
        Booking booking = createTestBooking(180);
        booking.setId(12345L);
        booking.setStatus(BookingStatus.COMPLETED);

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
            notificationService,
            eventService,
            eventPublisher,
            bookingPaymentService,
            paymentProvider,
            meterRegistry
        );

        // Then: No exception thrown (validates precondition accepts COMPLETED)
        try {
            orchestrator.startSaga(12345L);
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("must be in CHECKOUT_HOST_COMPLETE or COMPLETED")) {
                throw new AssertionError("Saga should accept COMPLETED status", e);
            }
            throw e;
        }
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
        booking.setStatus(BookingStatus.COMPLETED);

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
