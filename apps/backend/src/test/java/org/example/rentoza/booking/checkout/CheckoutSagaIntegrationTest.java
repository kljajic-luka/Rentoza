//package org.example.rentoza.booking.checkout;
//
//import io.micrometer.core.instrument.MeterRegistry;
//import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
//import org.example.rentoza.booking.Booking;
//import org.example.rentoza.booking.BookingRepository;
//import org.example.rentoza.booking.BookingStatus;
//import org.example.rentoza.booking.checkin.CheckInEventService;
//import org.example.rentoza.booking.checkin.CheckInPhotoRepository;
//import org.example.rentoza.booking.checkout.saga.CheckoutSagaOrchestrator;
//import org.example.rentoza.booking.checkout.saga.CheckoutSagaState;
//import org.example.rentoza.booking.checkout.saga.CheckoutSagaStateRepository;
//import org.example.rentoza.notification.NotificationService;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.ArgumentCaptor;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.math.BigDecimal;
//import java.time.Instant;
//import java.time.temporal.ChronoUnit;
//import java.util.Optional;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.ArgumentMatchers.eq;
//import static org.mockito.Mockito.*;
//
///**
// * Enterprise Integration Tests for Checkout Saga Integration.
// *
// * <h2>Test Coverage</h2>
// * <ul>
// *   <li>Saga invocation from CheckOutService</li>
// *   <li>Consistent late fee calculation (service vs saga)</li>
// *   <li>Idempotency of saga COMPLETE_BOOKING step</li>
// *   <li>Saga retry scenarios</li>
// *   <li>Data consistency (single source of truth)</li>
// * </ul>
// *
// * <h2>Architecture Validation</h2>
// * <ul>
// *   <li>Service delegates charge calculation to saga</li>
// *   <li>No duplicate fee calculation</li>
// *   <li>Saga handles all payment processing</li>
// *   <li>Metrics tracked for monitoring</li>
// * </ul>
// *
// * @see CheckOutService
// * @see CheckoutSagaOrchestrator
// */
//@ExtendWith(MockitoExtension.class)
//@DisplayName("Checkout Saga Integration - Enterprise Test Suite")
//class CheckoutSagaIntegrationTest {
//
//    @Mock
//    private BookingRepository bookingRepository;
//
//    @Mock
//    private CheckInEventService eventService;
//
//    @Mock
//    private CheckInPhotoRepository photoRepository;
//
//    @Mock
//    private NotificationService notificationService;
//
//    @Mock
//    private CheckoutSagaStateRepository sagaRepository;
//
//    @Mock
//    private CheckoutSagaOrchestrator checkoutSagaOrchestrator;
//
//    private MeterRegistry meterRegistry;
//    private CheckOutService checkOutService;
//
//    @BeforeEach
//    void setUp() {
//        meterRegistry = new SimpleMeterRegistry();
//
//        checkOutService = new CheckOutService(
//            bookingRepository,
//            eventService,
//            photoRepository,
//            notificationService,
//            checkoutSagaOrchestrator,
//            meterRegistry
//        );
//    }
//
//    @Test
//    @DisplayName("✅ Principal Pattern: Service delegates charge calculation to saga")
//    void testServiceDelegatesToSaga() {
//        // Given: Booking ready for completion
//        Booking booking = createTestBooking(180); // 3 hours late
//        booking.setId(12345L);
//        booking.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);
//
//        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
//
//        // When: Service completes checkout
//        checkOutService.completeCheckout(booking, 1L);
//
//        // Then: Saga is invoked
//        verify(checkoutSagaOrchestrator, times(1)).startSaga(eq(12345L));
//
//        // And: Booking status updated to COMPLETED
//        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
//        verify(bookingRepository).save(bookingCaptor.capture());
//        assertThat(bookingCaptor.getValue().getStatus()).isEqualTo(BookingStatus.COMPLETED);
//
//        // And: Metrics tracked
//        assertThat(meterRegistry.counter("checkout.saga.invoked").count()).isEqualTo(1.0);
//    }
//
//    @Test
//    @DisplayName("✅ Data Consistency: Service does NOT calculate late fee")
//    void testServiceDoesNotCalculateLateFee() {
//        // Given: Booking with late return (3 hours)
//        Booking booking = createTestBooking(180);
//        booking.setId(12345L);
//        booking.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);
//
//        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
//
//        // When: Service completes checkout
//        checkOutService.completeCheckout(booking, 1L);
//
//        // Then: Booking does NOT have late fee set by service
//        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
//        verify(bookingRepository).save(bookingCaptor.capture());
//
//        Booking savedBooking = bookingCaptor.getValue();
//        assertThat(savedBooking.getLateFeeAmount())
//            .describedAs("Service should NOT set late fee - saga is single source of truth")
//            .isNull();
//    }
//
//    @Test
//    @DisplayName("✅ Resilience: Service continues if saga invocation fails")
//    void testServiceContinuesOnSagaFailure() {
//        // Given: Booking ready for completion
//        Booking booking = createTestBooking(180);
//        booking.setId(12345L);
//        booking.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);
//
//        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
//
//        // And: Saga invocation throws exception
//        doThrow(new RuntimeException("Database timeout"))
//            .when(checkoutSagaOrchestrator).startSaga(any());
//
//        // When: Service completes checkout
//        checkOutService.completeCheckout(booking, 1L);
//
//        // Then: Service does NOT throw exception (resilient design)
//        // Checkout completes successfully
//        verify(bookingRepository).save(any(Booking.class));
//
//        // And: Notification still sent
//        verify(notificationService, atLeastOnce()).createNotification(any());
//    }
//
//    @Test
//    @DisplayName("✅ Idempotency: Saga accepts already COMPLETED booking")
//    void testSagaAcceptsCompletedStatus() {
//        // Given: Booking already COMPLETED by service
//        Booking booking = createTestBooking(180);
//        booking.setId(12345L);
//        booking.setStatus(BookingStatus.COMPLETED);  // Already completed
//
//        CheckoutSagaState saga = CheckoutSagaState.builder()
//            .bookingId(12345L)
//            .status(CheckoutSagaState.SagaStatus.PENDING)
//            .build();
//
//        when(bookingRepository.findById(12345L)).thenReturn(Optional.of(booking));
//        when(sagaRepository.findActiveSagaForBooking(12345L)).thenReturn(Optional.empty());
//        when(sagaRepository.save(any())).thenReturn(saga);
//
//        // When: Saga starts (should NOT throw exception)
//        CheckoutSagaOrchestrator orchestrator = new CheckoutSagaOrchestrator(
//            sagaRepository,
//            bookingRepository,
//            notificationService,
//            meterRegistry
//        );
//
//        // Then: No exception thrown (validates precondition accepts COMPLETED)
//        try {
//            orchestrator.startSaga(12345L);
//        } catch (IllegalStateException e) {
//            if (e.getMessage().contains("must be in CHECKOUT_HOST_COMPLETE or COMPLETED")) {
//                throw new AssertionError("Saga should accept COMPLETED status", e);
//            }
//            throw e;
//        }
//    }
//
//    @Test
//    @DisplayName("✅ Metrics: Saga invocation counter increments")
//    void testSagaInvocationMetrics() {
//        // Given: Multiple bookings
//        Booking booking1 = createTestBooking(60);
//        booking1.setId(1L);
//        booking1.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);
//
//        Booking booking2 = createTestBooking(120);
//        booking2.setId(2L);
//        booking2.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);
//
//        when(bookingRepository.save(any(Booking.class)))
//            .thenReturn(booking1)
//            .thenReturn(booking2);
//
//        // When: Service completes multiple checkouts
//        checkOutService.completeCheckout(booking1, 1L);
//        checkOutService.completeCheckout(booking2, 1L);
//
//        // Then: Saga invoked twice
//        verify(checkoutSagaOrchestrator, times(2)).startSaga(anyLong());
//
//        // And: Metrics reflect invocations
//        assertThat(meterRegistry.counter("checkout.saga.invoked").count()).isEqualTo(2.0);
//    }
//
//    @Test
//    @DisplayName("✅ Audit Trail: Event logged with saga delegation note")
//    void testAuditTrailIncludesSagaDelegation() {
//        // Given: Booking ready for completion
//        Booking booking = createTestBooking(180);
//        booking.setId(12345L);
//        booking.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);
//
//        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
//
//        // When: Service completes checkout
//        checkOutService.completeCheckout(booking, 1L);
//
//        // Then: Event recorded with saga delegation flag
//        ArgumentCaptor<java.util.Map> eventMapCaptor = ArgumentCaptor.forClass(java.util.Map.class);
//        verify(eventService).recordEvent(
//            any(Booking.class),
//            any(),
//            any(),
//            any(),
//            any(),
//            eventMapCaptor.capture()
//        );
//
//        assertThat(eventMapCaptor.getValue())
//            .containsEntry("chargeCalculationDelegatedToSaga", true)
//            .describedAs("Audit trail should indicate saga delegation");
//    }
//
//    @Test
//    @DisplayName("✅ Stress Test: Multiple concurrent saga invocations (idempotency)")
//    void testConcurrentSagaInvocationsAreIdempotent() {
//        // Given: Booking already has active saga
//        Booking booking = createTestBooking(180);
//        booking.setId(12345L);
//        booking.setStatus(BookingStatus.COMPLETED);
//
//        CheckoutSagaState existingSaga = CheckoutSagaState.builder()
//            .sagaId(java.util.UUID.randomUUID())
//            .bookingId(12345L)
//            .status(CheckoutSagaState.SagaStatus.RUNNING)
//            .build();
//
//        when(bookingRepository.findById(12345L)).thenReturn(Optional.of(booking));
//        when(sagaRepository.findActiveSagaForBooking(12345L))
//            .thenReturn(Optional.of(existingSaga));  // Active saga exists!
//
//        // When: Saga invoked again (concurrent invocation)
////        CheckoutSagaOrchestrator orchestrator = new CheckoutSagaOrchestrator(
////            sagaRepository,
////            bookingRepository,
////            notificationService,
////            meterRegistry
////        );
//
//        CheckoutSagaState result = orchestrator.startSaga(12345L);
//
//        // Then: Returns existing saga (idempotent - does not create duplicate)
//        assertThat(result.getSagaId()).isEqualTo(existingSaga.getSagaId());
//        assertThat(result.getStatus()).isEqualTo(CheckoutSagaState.SagaStatus.RUNNING);
//
//        // And: No new saga created
//        verify(sagaRepository, never()).save(any(CheckoutSagaState.class));
//    }
//
//    @Test
//    @DisplayName("✅ Edge Case: Saga invocation with OptimisticLockException recovery")
//    void testOptimisticLockExceptionHandling() {
//        // Given: Booking ready for completion
//        Booking booking = createTestBooking(180);
//        booking.setId(12345L);
//        booking.setStatus(BookingStatus.CHECKOUT_HOST_COMPLETE);
//
//        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
//
//        // And: Saga invocation throws OptimisticLockException (concurrent modification)
//        doThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
//            CheckoutSagaState.class, "Version mismatch"))
//            .when(checkoutSagaOrchestrator).startSaga(any());
//
//        // When: Service completes checkout (should NOT fail)
//        checkOutService.completeCheckout(booking, 1L);
//
//        // Then: Checkout still completes successfully (resilient design)
//        verify(bookingRepository).save(any(Booking.class));
//
//        // And: Exception tracked in metrics
//        assertThat(meterRegistry.counter("checkout.saga.invocation.exceptions").count())
//            .isEqualTo(1.0)
//            .describedAs("Exception counter should increment");
//
//        // And: Saga not marked as invoked (failed before execution)
//        assertThat(meterRegistry.counter("checkout.saga.invoked").count())
//            .isEqualTo(0.0)
//            .describedAs("Invocation counter should NOT increment on exception");
//    }
//
//    // ========== Test Data Helpers ==========
//
//    private Booking createTestBooking(int lateMinutes) {
//        Booking booking = new Booking();
//
//        Instant now = Instant.now();
//        Instant scheduledReturn = now.minus(lateMinutes, ChronoUnit.MINUTES);
//
//        booking.setScheduledReturnTime(scheduledReturn);
//        booking.setTripEndedAt(now);
//        booking.setTripStartedAt(now.minus(7, ChronoUnit.DAYS));
//
//        booking.setStartOdometer(10000);
//        booking.setEndOdometer(11500);
//
//        booking.setStartFuelLevel(100);
//        booking.setEndFuelLevel(50);
//
//        booking.setCheckoutSessionId(String.valueOf(java.util.UUID.randomUUID()));
//
//        return booking;
//    }
//}
