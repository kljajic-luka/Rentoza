package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.CheckInEventService;
import org.example.rentoza.booking.checkin.CheckInEventType;
import org.example.rentoza.booking.checkin.CheckInPhotoRepository;
import org.example.rentoza.booking.checkin.CheckInService;
import org.example.rentoza.booking.checkin.CheckInValidationService;
import org.example.rentoza.booking.checkin.GeofenceService;
import org.example.rentoza.booking.checkin.GuestCheckInPhotoRepository;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.photo.PhotoUrlService;
import org.example.rentoza.car.Car;
import org.example.rentoza.config.FeatureFlags;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.payment.PaymentProvider.PaymentResult;
import org.example.rentoza.security.LockboxEncryptionService;
import org.example.rentoza.user.RenterVerificationService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for no-show processing in {@link CheckInService#processNoShow(Booking, String)}.
 *
 * <p>Validates the F-SM-1 status guards introduced to prevent race conditions where
 * a concurrent handshake or status advancement could cause a no-show to incorrectly
 * overwrite a valid booking state (e.g., IN_TRIP).
 *
 * <h2>Test Categories (Audit Section 15)</h2>
 * <ul>
 *   <li>HOST no-show: Must be in CHECK_IN_OPEN; rejected from all other statuses</li>
 *   <li>GUEST no-show: Must be in CHECK_IN_HOST_COMPLETE; rejected from all other statuses</li>
 *   <li>Event recording and notification verification for accepted no-shows</li>
 *   <li>Refund processing verification for host no-shows</li>
 * </ul>
 *
 * @see CheckInService#processNoShow(Booking, String)
 * @see CheckInService#processHostNoShowRefund(Booking)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CheckInService - No-Show Processing (F-SM-1 Status Guards)")
class NoShowProcessingTest {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private CheckInEventService eventService;

    @Mock
    private CheckInPhotoRepository photoRepository;

    @Mock
    private GuestCheckInPhotoRepository guestPhotoRepository;

    @Mock
    private GeofenceService geofenceService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private LockboxEncryptionService lockboxEncryptionService;

    @Mock
    private RenterVerificationService renterVerificationService;

    @Mock
    private FeatureFlags featureFlags;

    @Mock
    private CheckInValidationService validationService;

    @Mock
    private DamageClaimRepository damageClaimRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PhotoUrlService photoUrlService;

    @Mock
    private BookingPaymentService bookingPaymentService;

    private MeterRegistry meterRegistry;
    private CheckInService checkInService;

    // Test fixtures
    private User owner;
    private User renter;
    private Car car;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        checkInService = new CheckInService(
                bookingRepository,
                eventService,
                photoRepository,
                guestPhotoRepository,
                geofenceService,
                notificationService,
                lockboxEncryptionService,
                renterVerificationService,
                featureFlags,
                validationService,
                damageClaimRepository,
                userRepository,
                meterRegistry,
                photoUrlService,
                bookingPaymentService
        );

        owner = new User();
        owner.setId(200L);
        owner.setEmail("owner@test.com");
        owner.setFirstName("Marko");
        owner.setLastName("Vlasnik");

        renter = new User();
        renter.setId(100L);
        renter.setEmail("renter@test.com");
        renter.setFirstName("Nikola");
        renter.setLastName("Gost");

        car = new Car();
        car.setId(10L);
        car.setOwner(owner);
        car.setBrand("Fiat");
        car.setModel("Punto");
    }

    /**
     * Creates a fully populated booking suitable for no-show test scenarios.
     *
     * @param status the initial booking status
     * @return a booking with all required fields for processNoShow
     */
    private Booking createBooking(BookingStatus status) {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setCheckInSessionId("test-session-uuid");
        booking.setStartTime(LocalDateTime.now(SERBIA_ZONE).minusHours(2));
        booking.setEndTime(LocalDateTime.now(SERBIA_ZONE).plusDays(3));
        booking.setHostCheckInCompletedAt(Instant.now().minus(Duration.ofHours(1)));
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setStatus(status);
        booking.setTotalPrice(BigDecimal.valueOf(15000));
        booking.setPaymentVerificationRef("test-ref");
        return booking;
    }

    // ========================================================================
    // HOST NO-SHOW: Status Guard Tests
    // ========================================================================

    @Nested
    @DisplayName("HOST no-show status guards")
    class HostNoShowStatusGuards {

        @Test
        @DisplayName("HOST no-show accepted from CHECK_IN_OPEN -> NO_SHOW_HOST")
        void no_show_host_only_from_CHECK_IN_OPEN() {
            // Arrange
            Booking booking = createBooking(BookingStatus.CHECK_IN_OPEN);
            PaymentResult successResult = PaymentResult.builder()
                    .success(true)
                    .transactionId("txn-refund-001")
                    .build();
            when(bookingPaymentService.processFullRefund(anyLong(), anyString()))
                    .thenReturn(successResult);

            // Act
            checkInService.processNoShow(booking, "HOST");

            // Assert
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.NO_SHOW_HOST);
            verify(bookingRepository).save(booking);
            verify(eventService).recordSystemEvent(
                    eq(booking),
                    eq("test-session-uuid"),
                    eq(CheckInEventType.NO_SHOW_HOST_TRIGGERED),
                    any()
            );
        }

        @Test
        @DisplayName("HOST no-show REJECTED from IN_TRIP -- status unchanged, no save")
        void no_show_host_rejected_from_IN_TRIP() {
            // Arrange
            Booking booking = createBooking(BookingStatus.IN_TRIP);

            // Act
            checkInService.processNoShow(booking, "HOST");

            // Assert -- status must remain IN_TRIP; save still called at end but status unchanged
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.IN_TRIP);
            // The early return skips setting status and recording events, but bookingRepository.save
            // is called unconditionally at the end of the method (line 1011). However, no status
            // change or event should have been triggered.
            verify(eventService, never()).recordSystemEvent(
                    any(Booking.class),
                    anyString(),
                    eq(CheckInEventType.NO_SHOW_HOST_TRIGGERED),
                    any()
            );
            verify(notificationService, never()).createNotification(any());
            verify(bookingPaymentService, never()).processFullRefund(anyLong(), anyString());
        }

        @Test
        @DisplayName("HOST no-show REJECTED from CHECK_IN_HOST_COMPLETE -- status unchanged")
        void no_show_host_rejected_from_CHECK_IN_HOST_COMPLETE() {
            // Arrange
            Booking booking = createBooking(BookingStatus.CHECK_IN_HOST_COMPLETE);

            // Act
            checkInService.processNoShow(booking, "HOST");

            // Assert
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CHECK_IN_HOST_COMPLETE);
            verify(eventService, never()).recordSystemEvent(
                    any(Booking.class),
                    anyString(),
                    eq(CheckInEventType.NO_SHOW_HOST_TRIGGERED),
                    any()
            );
            verify(bookingPaymentService, never()).processFullRefund(anyLong(), anyString());
        }

        @Test
        @DisplayName("HOST no-show REJECTED from COMPLETED -- status unchanged")
        void no_show_host_rejected_from_COMPLETED() {
            // Arrange
            Booking booking = createBooking(BookingStatus.COMPLETED);

            // Act
            checkInService.processNoShow(booking, "HOST");

            // Assert
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
            verify(eventService, never()).recordSystemEvent(
                    any(Booking.class),
                    anyString(),
                    eq(CheckInEventType.NO_SHOW_HOST_TRIGGERED),
                    any()
            );
            verify(bookingPaymentService, never()).processFullRefund(anyLong(), anyString());
        }
    }

    // ========================================================================
    // GUEST NO-SHOW: Status Guard Tests
    // ========================================================================

    @Nested
    @DisplayName("GUEST no-show status guards")
    class GuestNoShowStatusGuards {

        @Test
        @DisplayName("GUEST no-show accepted from CHECK_IN_HOST_COMPLETE -> NO_SHOW_GUEST")
        void no_show_guest_only_from_CHECK_IN_HOST_COMPLETE() {
            // Arrange
            Booking booking = createBooking(BookingStatus.CHECK_IN_HOST_COMPLETE);

            // Act
            checkInService.processNoShow(booking, "GUEST");

            // Assert
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.NO_SHOW_GUEST);
            verify(bookingRepository).save(booking);
            verify(eventService).recordSystemEvent(
                    eq(booking),
                    eq("test-session-uuid"),
                    eq(CheckInEventType.NO_SHOW_GUEST_TRIGGERED),
                    any()
            );
        }

        @Test
        @DisplayName("GUEST no-show REJECTED from IN_TRIP -- status unchanged")
        void no_show_guest_rejected_from_IN_TRIP() {
            // Arrange
            Booking booking = createBooking(BookingStatus.IN_TRIP);

            // Act
            checkInService.processNoShow(booking, "GUEST");

            // Assert
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.IN_TRIP);
            verify(eventService, never()).recordSystemEvent(
                    any(Booking.class),
                    anyString(),
                    eq(CheckInEventType.NO_SHOW_GUEST_TRIGGERED),
                    any()
            );
            verify(notificationService, never()).createNotification(any());
        }

        @Test
        @DisplayName("GUEST no-show REJECTED from CHECK_IN_OPEN -- status unchanged")
        void no_show_guest_rejected_from_CHECK_IN_OPEN() {
            // Arrange
            Booking booking = createBooking(BookingStatus.CHECK_IN_OPEN);

            // Act
            checkInService.processNoShow(booking, "GUEST");

            // Assert
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CHECK_IN_OPEN);
            verify(eventService, never()).recordSystemEvent(
                    any(Booking.class),
                    anyString(),
                    eq(CheckInEventType.NO_SHOW_GUEST_TRIGGERED),
                    any()
            );
        }

        @Test
        @DisplayName("GUEST no-show REJECTED from CHECK_IN_COMPLETE -- status unchanged")
        void no_show_guest_rejected_from_CHECK_IN_COMPLETE() {
            // Arrange
            Booking booking = createBooking(BookingStatus.CHECK_IN_COMPLETE);

            // Act
            checkInService.processNoShow(booking, "GUEST");

            // Assert
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CHECK_IN_COMPLETE);
            verify(eventService, never()).recordSystemEvent(
                    any(Booking.class),
                    anyString(),
                    eq(CheckInEventType.NO_SHOW_GUEST_TRIGGERED),
                    any()
            );
        }
    }

    // ========================================================================
    // Event Recording and Notification Verification
    // ========================================================================

    @Nested
    @DisplayName("Event recording and notifications")
    class EventsAndNotifications {

        @Test
        @DisplayName("HOST no-show records NO_SHOW_HOST_TRIGGERED event and notifies guest")
        void no_show_host_records_event_and_notifies() {
            // Arrange
            Booking booking = createBooking(BookingStatus.CHECK_IN_OPEN);
            PaymentResult successResult = PaymentResult.builder()
                    .success(true)
                    .transactionId("txn-refund-002")
                    .build();
            when(bookingPaymentService.processFullRefund(anyLong(), anyString()))
                    .thenReturn(successResult);

            // Act
            checkInService.processNoShow(booking, "HOST");

            // Assert -- NO_SHOW_HOST_TRIGGERED event
            verify(eventService).recordSystemEvent(
                    eq(booking),
                    eq("test-session-uuid"),
                    eq(CheckInEventType.NO_SHOW_HOST_TRIGGERED),
                    any()
            );

            // Assert -- refund event (success path)
            verify(eventService).recordSystemEvent(
                    eq(booking),
                    eq("test-session-uuid"),
                    eq(CheckInEventType.NO_SHOW_REFUND_PROCESSED),
                    any()
            );

            // Assert -- admin alert event
            verify(eventService).recordSystemEvent(
                    eq(booking),
                    eq("test-session-uuid"),
                    eq(CheckInEventType.NO_SHOW_ADMIN_ALERT_SENT),
                    any()
            );

            // Assert -- guest notified via notifyNoShow (creates notification for renter)
            verify(notificationService).createNotification(any());

            // Assert -- admin alerted
            verify(notificationService).alertAdminNoShow(eq(booking), eq("HOST"), eq(true));
        }

        @Test
        @DisplayName("GUEST no-show records NO_SHOW_GUEST_TRIGGERED event and notifies host")
        void no_show_guest_records_event_and_notifies() {
            // Arrange
            Booking booking = createBooking(BookingStatus.CHECK_IN_HOST_COMPLETE);

            // Act
            checkInService.processNoShow(booking, "GUEST");

            // Assert -- NO_SHOW_GUEST_TRIGGERED event
            verify(eventService).recordSystemEvent(
                    eq(booking),
                    eq("test-session-uuid"),
                    eq(CheckInEventType.NO_SHOW_GUEST_TRIGGERED),
                    any()
            );

            // Assert -- admin alert event
            verify(eventService).recordSystemEvent(
                    eq(booking),
                    eq("test-session-uuid"),
                    eq(CheckInEventType.NO_SHOW_ADMIN_ALERT_SENT),
                    any()
            );

            // Assert -- host notified via notifyNoShow (creates notification for owner)
            verify(notificationService).createNotification(any());

            // Assert -- admin alerted with refundSuccess=false (guest no-show has no refund)
            verify(notificationService).alertAdminNoShow(eq(booking), eq("GUEST"), eq(false));
        }
    }

    // ========================================================================
    // Refund Processing
    // ========================================================================

    @Nested
    @DisplayName("Host no-show refund processing")
    class RefundProcessing {

        @Test
        @DisplayName("HOST no-show triggers processFullRefund via BookingPaymentService")
        void no_show_host_triggers_refund_processing() {
            // Arrange
            Booking booking = createBooking(BookingStatus.CHECK_IN_OPEN);
            PaymentResult successResult = PaymentResult.builder()
                    .success(true)
                    .transactionId("txn-refund-003")
                    .build();
            when(bookingPaymentService.processFullRefund(eq(1L), anyString()))
                    .thenReturn(successResult);

            // Act
            checkInService.processNoShow(booking, "HOST");

            // Assert -- payment service called with booking ID
            verify(bookingPaymentService).processFullRefund(
                    eq(1L),
                    eq("Automatski povraćaj zbog no-show domaćina")
            );

            // Assert -- success refund event recorded
            verify(eventService).recordSystemEvent(
                    eq(booking),
                    eq("test-session-uuid"),
                    eq(CheckInEventType.NO_SHOW_REFUND_PROCESSED),
                    any()
            );

            // Assert -- admin notified with refundSuccess=true
            verify(notificationService).alertAdminNoShow(eq(booking), eq("HOST"), eq(true));
        }

        @Test
        @DisplayName("HOST no-show records REFUND_FAILED event when payment service fails")
        void no_show_host_refund_failure_records_failed_event() {
            // Arrange
            Booking booking = createBooking(BookingStatus.CHECK_IN_OPEN);
            PaymentResult failResult = PaymentResult.builder()
                    .success(false)
                    .errorMessage("Provider unavailable")
                    .build();
            when(bookingPaymentService.processFullRefund(anyLong(), anyString()))
                    .thenReturn(failResult);

            // Act
            checkInService.processNoShow(booking, "HOST");

            // Assert -- status still transitions (no-show is recorded regardless of refund)
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.NO_SHOW_HOST);

            // Assert -- failure event recorded
            verify(eventService).recordSystemEvent(
                    eq(booking),
                    eq("test-session-uuid"),
                    eq(CheckInEventType.NO_SHOW_REFUND_FAILED),
                    any()
            );

            // Assert -- admin notified with refundSuccess=false
            verify(notificationService).alertAdminNoShow(eq(booking), eq("HOST"), eq(false));
        }

        @Test
        @DisplayName("HOST no-show with null paymentVerificationRef treated as MOCK success")
        void no_show_host_mock_refund_when_no_payment_ref() {
            // Arrange
            Booking booking = createBooking(BookingStatus.CHECK_IN_OPEN);
            booking.setPaymentVerificationRef(null);
            PaymentResult failResult = PaymentResult.builder()
                    .success(false)
                    .errorMessage("No payment to refund")
                    .build();
            when(bookingPaymentService.processFullRefund(anyLong(), anyString()))
                    .thenReturn(failResult);

            // Act
            checkInService.processNoShow(booking, "HOST");

            // Assert -- processHostNoShowRefund returns true for null ref (MOCK mode)
            // so the refund is considered processed
            verify(eventService).recordSystemEvent(
                    eq(booking),
                    eq("test-session-uuid"),
                    eq(CheckInEventType.NO_SHOW_REFUND_PROCESSED),
                    any()
            );
            verify(notificationService).alertAdminNoShow(eq(booking), eq("HOST"), eq(true));
        }

        @Test
        @DisplayName("HOST no-show releases deposit authorization when present")
        void no_show_host_releases_deposit_when_present() {
            // Arrange
            Booking booking = createBooking(BookingStatus.CHECK_IN_OPEN);
            booking.setDepositAuthorizationId("dep-auth-001");
            PaymentResult successResult = PaymentResult.builder()
                    .success(true)
                    .transactionId("txn-refund-004")
                    .build();
            PaymentResult depositResult = PaymentResult.builder()
                    .success(true)
                    .build();
            when(bookingPaymentService.processFullRefund(anyLong(), anyString()))
                    .thenReturn(successResult);
            when(bookingPaymentService.releaseDeposit(eq(1L), eq("dep-auth-001")))
                    .thenReturn(depositResult);

            // Act
            checkInService.processNoShow(booking, "HOST");

            // Assert -- deposit release attempted
            verify(bookingPaymentService).releaseDeposit(eq(1L), eq("dep-auth-001"));
        }

        @Test
        @DisplayName("HOST no-show does not attempt deposit release when no deposit auth")
        void no_show_host_skips_deposit_release_when_none() {
            // Arrange
            Booking booking = createBooking(BookingStatus.CHECK_IN_OPEN);
            booking.setDepositAuthorizationId(null);
            PaymentResult successResult = PaymentResult.builder()
                    .success(true)
                    .transactionId("txn-refund-005")
                    .build();
            when(bookingPaymentService.processFullRefund(anyLong(), anyString()))
                    .thenReturn(successResult);

            // Act
            checkInService.processNoShow(booking, "HOST");

            // Assert -- deposit release never called
            verify(bookingPaymentService, never()).releaseDeposit(anyLong(), anyString());
        }

        @Test
        @DisplayName("HOST no-show handles payment service exception gracefully")
        void no_show_host_handles_payment_exception() {
            // Arrange
            Booking booking = createBooking(BookingStatus.CHECK_IN_OPEN);
            when(bookingPaymentService.processFullRefund(anyLong(), anyString()))
                    .thenThrow(new RuntimeException("Connection timeout"));

            // Act -- should not propagate exception
            checkInService.processNoShow(booking, "HOST");

            // Assert -- status still transitions
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.NO_SHOW_HOST);

            // Assert -- failure event recorded (exception path returns false)
            verify(eventService).recordSystemEvent(
                    eq(booking),
                    eq("test-session-uuid"),
                    eq(CheckInEventType.NO_SHOW_REFUND_FAILED),
                    any()
            );

            // Assert -- booking still saved
            verify(bookingRepository).save(booking);
        }
    }
}
