package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.booking.cancellation.CancellationSettlementService;
import org.example.rentoza.booking.checkin.cqrs.CheckInCommandService;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.car.Car;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.payment.PaymentProvider.PaymentResult;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F-SM-2 gap closure: Direct tests for {@link CheckInScheduler#detectStaleHandshakes()}
 * and specifically the status guard in {@code cancelStaleHandshakeBooking()}.
 *
 * <p>Validates that the scheduler correctly handles:</p>
 * <ul>
 *   <li>Normal path: CHECK_IN_COMPLETE booking → CANCELLED with refund + notifications</li>
 *   <li>Race guard: If status advanced to IN_TRIP between query and processing, skip gracefully</li>
 *   <li>Empty result: No stale handshakes found → no processing</li>
 *   <li>Idempotency lock: Duplicate scheduler run skipped</li>
 * </ul>
 *
 * @see CheckInScheduler#detectStaleHandshakes()
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("F-SM-2: Stale Handshake Scheduler Tests")
class StaleHandshakeSchedulerTest {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    @Mock private CheckInService checkInService;
    @Mock private CheckInCommandService checkInCommandService;
    @Mock private CheckInEventService eventService;
    @Mock private SchedulerIdempotencyService idempotencyService;
    @Mock private BookingRepository bookingRepository;
    @Mock private DamageClaimRepository damageClaimRepository;
    @Mock private NotificationService notificationService;
        @Mock private BookingPaymentService bookingPaymentService;
        @Mock private CancellationSettlementService cancellationSettlementService;

    private CheckInScheduler scheduler;

    @BeforeEach
    void setUp() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();

        scheduler = new CheckInScheduler(
                checkInService,
                checkInCommandService,
                eventService,
                idempotencyService,
                bookingRepository,
                damageClaimRepository,
                notificationService,
                bookingPaymentService,
                cancellationSettlementService,
                meterRegistry
        );

        // Set @Value fields that @InjectMocks cannot populate
        ReflectionTestUtils.setField(scheduler, "windowHoursBeforeTrip", 2);
        ReflectionTestUtils.setField(scheduler, "disputeTimeoutHours", 24);
        ReflectionTestUtils.setField(scheduler, "noShowMinutesAfterTripStart", 120);
        ReflectionTestUtils.setField(scheduler, "handshakeTimeoutMinutes", 45);
        ReflectionTestUtils.setField(scheduler, "noShowDiagnosticsEnabled", false);

        // Default: grant idempotency lock
        when(idempotencyService.tryAcquireLock(anyString(), any(Duration.class)))
                .thenReturn(true);
    }

    /**
     * Build a booking in the given status with all fields needed for stale handshake processing.
     */
    private Booking createBooking(BookingStatus status) {
        User renter = new User();
        renter.setId(10L);
        renter.setFirstName("Test");
        renter.setLastName("Renter");

        User owner = new User();
        owner.setId(20L);
        owner.setFirstName("Test");
        owner.setLastName("Owner");

        Car car = new Car();
        car.setId(100L);
        car.setOwner(owner);

        Booking booking = new Booking();
        booking.setId(1L);
        booking.setStatus(status);
        booking.setRenter(renter);
        booking.setCar(car);
        booking.setCheckInSessionId("session-uuid-001");
        booking.setStartTime(LocalDateTime.now(SERBIA_ZONE).minusHours(2));
        booking.setEndTime(LocalDateTime.now(SERBIA_ZONE).plusDays(3));
        booking.setTotalPrice(new BigDecimal("15000.00"));
        booking.setGuestCheckInCompletedAt(Instant.now().minus(Duration.ofHours(1)));
        booking.setPaymentVerificationRef("test-payment-ref");
        return booking;
    }

    // =========================================================================
    // Normal path: CHECK_IN_COMPLETE → CANCELLED
    // =========================================================================

    @Nested
    @DisplayName("Normal stale handshake cancellation")
    class NormalCancellation {

        @Test
        @DisplayName("detectStaleHandshakes cancels CHECK_IN_COMPLETE booking and processes refund")
        void cancels_stale_check_in_complete_booking() {
            Booking booking = createBooking(BookingStatus.CHECK_IN_COMPLETE);

            when(bookingRepository.findStaleCheckInHandshakes(
                    eq(BookingStatus.CHECK_IN_COMPLETE), any(Instant.class)))
                    .thenReturn(List.of(booking));

            CancellationRecord record = new CancellationRecord();
            record.setBooking(booking);
            when(cancellationSettlementService.beginAndAttemptFullRefundSettlement(any(), any(), any(), anyString(), anyString(), anyString()))
                    .thenAnswer(invocation -> {
                        booking.setStatus(BookingStatus.CANCELLED);
                                                booking.setCancelledAt(LocalDateTime.now(SERBIA_ZONE));
                        return new CancellationSettlementService.SettlementAttemptResult(record, true);
                    });

            // Act
            scheduler.detectStaleHandshakes();

            // Assert — booking cancelled
            assertThat(booking.getStatus())
                    .as("Stale handshake booking must be CANCELLED")
                    .isEqualTo(BookingStatus.CANCELLED);

            assertThat(booking.getCancelledAt())
                    .as("cancelledAt must be set")
                    .isNotNull();

            // Assert — refund attempted
            verify(cancellationSettlementService).beginAndAttemptFullRefundSettlement(eq(booking), any(), any(), anyString(), anyString(), anyString());

            // Assert — events recorded
            verify(eventService).recordSystemEvent(
                    eq(booking), eq("session-uuid-001"),
                    eq(CheckInEventType.HANDSHAKE_TIMEOUT_AUTO_CANCELLED),
                    any());

            // Assert — both renter and owner notified
            verify(notificationService, times(2)).createNotification(any());

            // Assert — admin alerted
            verify(notificationService).alertAdminNoShow(eq(booking), eq("HANDSHAKE_TIMEOUT"), eq(true));
        }

        @Test
        @DisplayName("detectStaleHandshakes records refund success event when refund succeeds")
        void records_refund_success_event() {
            Booking booking = createBooking(BookingStatus.CHECK_IN_COMPLETE);

            when(bookingRepository.findStaleCheckInHandshakes(any(), any()))
                    .thenReturn(List.of(booking));
            CancellationRecord record = new CancellationRecord();
            record.setBooking(booking);
            when(cancellationSettlementService.beginAndAttemptFullRefundSettlement(any(), any(), any(), anyString(), anyString(), anyString()))
                    .thenReturn(new CancellationSettlementService.SettlementAttemptResult(record, true));

            // Act
            scheduler.detectStaleHandshakes();

            // Assert — refund processed event (not failed)
            verify(eventService).recordSystemEvent(
                    eq(booking), eq("session-uuid-001"),
                    eq(CheckInEventType.NO_SHOW_REFUND_PROCESSED),
                    any());

            verify(eventService, never()).recordSystemEvent(
                    any(), any(),
                    eq(CheckInEventType.NO_SHOW_REFUND_FAILED),
                    any());
        }

        @Test
        @DisplayName("detectStaleHandshakes records refund failure event when refund fails")
        void records_refund_failure_event() {
            Booking booking = createBooking(BookingStatus.CHECK_IN_COMPLETE);

            when(bookingRepository.findStaleCheckInHandshakes(any(), any()))
                    .thenReturn(List.of(booking));
            CancellationRecord record = new CancellationRecord();
            record.setBooking(booking);
            when(cancellationSettlementService.beginAndAttemptFullRefundSettlement(any(), any(), any(), anyString(), anyString(), anyString()))
                    .thenAnswer(invocation -> {
                        booking.setStatus(BookingStatus.CANCELLATION_PENDING_SETTLEMENT);
                        return new CancellationSettlementService.SettlementAttemptResult(record, false);
                    });

            // Act
            scheduler.detectStaleHandshakes();

            // Assert — refund failed event
            verify(eventService).recordSystemEvent(
                    eq(booking), eq("session-uuid-001"),
                    eq(CheckInEventType.NO_SHOW_REFUND_FAILED),
                    any());

            // Assert — admin notified with refundSuccess=false
            verify(notificationService).alertAdminNoShow(eq(booking), eq("HANDSHAKE_TIMEOUT"), eq(false));
        }
    }

    // =========================================================================
    // F-SM-2: Race condition guard
    // =========================================================================

    @Nested
    @DisplayName("F-SM-2: Status guard against concurrent handshake")
    class StatusGuard {

        @Test
        @DisplayName("cancelStaleHandshakeBooking skips when status advanced to IN_TRIP (race with confirmHandshake)")
        void skips_when_status_advanced_to_in_trip() {
            // Simulate: query found CHECK_IN_COMPLETE, but confirmHandshake() advanced to IN_TRIP
            Booking booking = createBooking(BookingStatus.IN_TRIP);

            when(bookingRepository.findStaleCheckInHandshakes(any(), any()))
                    .thenReturn(List.of(booking));

            // Act
            scheduler.detectStaleHandshakes();

            // Assert — status unchanged (still IN_TRIP)
            assertThat(booking.getStatus())
                    .as("IN_TRIP booking must NOT be cancelled by stale handshake handler")
                    .isEqualTo(BookingStatus.IN_TRIP);

            // Assert — no save, no refund, no events, no notifications
            verify(bookingRepository, never()).save(any());
            verify(cancellationSettlementService, never()).beginAndAttemptFullRefundSettlement(any(), any(), any(), anyString(), anyString(), anyString());
            verify(eventService, never()).recordSystemEvent(any(), any(), any(), any());
            verify(notificationService, never()).createNotification(any());
            verify(notificationService, never()).alertAdminNoShow(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("cancelStaleHandshakeBooking skips when status is CANCELLED (already cancelled)")
        void skips_when_already_cancelled() {
            Booking booking = createBooking(BookingStatus.CANCELLED);

            when(bookingRepository.findStaleCheckInHandshakes(any(), any()))
                    .thenReturn(List.of(booking));

            // Act
            scheduler.detectStaleHandshakes();

            // Assert
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            verify(bookingRepository, never()).save(any());
            verify(cancellationSettlementService, never()).beginAndAttemptFullRefundSettlement(any(), any(), any(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("cancelStaleHandshakeBooking skips when status is COMPLETED (already completed)")
        void skips_when_already_completed() {
            Booking booking = createBooking(BookingStatus.COMPLETED);

            when(bookingRepository.findStaleCheckInHandshakes(any(), any()))
                    .thenReturn(List.of(booking));

            // Act
            scheduler.detectStaleHandshakes();

            // Assert
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
            verify(bookingRepository, never()).save(any());
        }
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("detectStaleHandshakes does nothing when no stale bookings found")
        void no_stale_handshakes_found() {
            when(bookingRepository.findStaleCheckInHandshakes(any(), any()))
                    .thenReturn(List.of());

            // Act
            scheduler.detectStaleHandshakes();

            // Assert — no processing at all
            verify(bookingRepository, never()).save(any());
                        verify(cancellationSettlementService, never()).beginAndAttemptFullRefundSettlement(any(), any(), any(), anyString(), anyString(), anyString());
            verify(notificationService, never()).createNotification(any());
        }

        @Test
        @DisplayName("detectStaleHandshakes skips when idempotency lock not acquired")
        void skips_when_lock_not_acquired() {
            when(idempotencyService.tryAcquireLock(anyString(), any(Duration.class)))
                    .thenReturn(false);

            // Act
            scheduler.detectStaleHandshakes();

            // Assert — no query, no processing
            verify(bookingRepository, never()).findStaleCheckInHandshakes(any(), any());
            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("detectStaleHandshakes processes multiple bookings, skipping raced ones")
        void processes_multiple_bookings_with_mixed_statuses() {
            Booking stale = createBooking(BookingStatus.CHECK_IN_COMPLETE);
            stale.setId(1L);

            Booking raced = createBooking(BookingStatus.IN_TRIP);
            raced.setId(2L);
            raced.setCheckInSessionId("session-uuid-002");

            when(bookingRepository.findStaleCheckInHandshakes(any(), any()))
                    .thenReturn(List.of(stale, raced));
            CancellationRecord record = new CancellationRecord();
            record.setBooking(stale);
            when(cancellationSettlementService.beginAndAttemptFullRefundSettlement(eq(stale), any(), any(), anyString(), anyString(), anyString()))
                    .thenAnswer(invocation -> {
                        stale.setStatus(BookingStatus.CANCELLED);
                        return new CancellationSettlementService.SettlementAttemptResult(record, true);
                    });

            // Act
            scheduler.detectStaleHandshakes();

            // Assert — stale booking cancelled
            assertThat(stale.getStatus()).isEqualTo(BookingStatus.CANCELLED);

            // Assert — raced booking untouched
            assertThat(raced.getStatus()).isEqualTo(BookingStatus.IN_TRIP);

                        verify(cancellationSettlementService).beginAndAttemptFullRefundSettlement(eq(stale), any(), any(), anyString(), anyString(), anyString());
                        verify(cancellationSettlementService, never()).beginAndAttemptFullRefundSettlement(eq(raced), any(), any(), anyString(), anyString(), anyString());
        }
    }
}
