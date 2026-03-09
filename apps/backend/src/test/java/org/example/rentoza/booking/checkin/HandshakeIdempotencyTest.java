package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.dto.CheckInStatusDTO;
import org.example.rentoza.booking.checkin.dto.GuestConditionAcknowledgmentDTO;
import org.example.rentoza.booking.checkin.dto.HandshakeConfirmationDTO;
import org.example.rentoza.booking.dispute.DamageClaimRepository;
import org.example.rentoza.booking.photo.PhotoUrlService;
import org.example.rentoza.car.Car;
import org.example.rentoza.config.FeatureFlags;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.payment.BookingPaymentService;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for R4 (lock timeout) and R5 (guest acknowledgment idempotency).
 *
 * <p>R4 is verified structurally (annotation presence) and is best tested
 * as a real PostgreSQL integration test with concurrent transactions.
 * This test class focuses on R5 behavioral verification via Mockito.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("R4/R5: Handshake Lock Timeout & Guest Acknowledgment Idempotency")
class HandshakeIdempotencyTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private CheckInEventService eventService;
    @Mock private CheckInPhotoRepository photoRepository;
    @Mock private GuestCheckInPhotoRepository guestPhotoRepository;
    @Mock private GeofenceService geofenceService;
    @Mock private NotificationService notificationService;
    @Mock private LockboxEncryptionService lockboxEncryptionService;
    @Mock private RenterVerificationService renterVerificationService;
    @Mock private FeatureFlags featureFlags;
    @Mock private CheckInValidationService validationService;
    @Mock private DamageClaimRepository damageClaimRepository;
    @Mock private UserRepository userRepository;
    @Mock private PhotoUrlService photoUrlService;
    @Mock private BookingPaymentService bookingPaymentService;
    @Mock private org.example.rentoza.booking.RentalAgreementService rentalAgreementService;
    @Mock private CheckInAttestationService checkInAttestationService;

    private CheckInService checkInService;
    private Booking booking;
    private User host;
    private User renter;
    private Car car;

    @BeforeEach
    void setUp() {
        MeterRegistry registry = new SimpleMeterRegistry();

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
                registry,
                photoUrlService,
                bookingPaymentService,
                rentalAgreementService,
                checkInAttestationService
        );

        // Users
        host = new User();
        host.setId(100L);
        host.setFirstName("Host");
        host.setLastName("User");

        renter = new User();
        renter.setId(200L);
        renter.setFirstName("Renter");
        renter.setLastName("User");

        // Car with owner
        car = new Car();
        car.setId(1L);
        car.setOwner(host);

        // Booking
        booking = new Booking();
        booking.setId(1L);
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setCheckInSessionId("session-1");
        booking.setCheckInEvents(new ArrayList<>());
        booking.setCheckInPhotos(new ArrayList<>());
    }

    // ========================================================================
    // R5: Guest Acknowledgment Idempotency Tests
    // ========================================================================

    @Nested
    @DisplayName("R5: Guest Acknowledgment Idempotency Guard")
    class GuestAcknowledgmentIdempotencyTests {

        @Test
        @DisplayName("R5: Second acknowledgment after CHECK_IN_COMPLETE returns cached result (no duplicate events)")
        void shouldReturnCachedResultForDuplicateAcknowledgment() {
            // Arrange: booking already has guest completed
            booking.setStatus(BookingStatus.CHECK_IN_COMPLETE);
            booking.setGuestCheckInCompletedAt(Instant.now().minusSeconds(60));

            when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));

            GuestConditionAcknowledgmentDTO dto = new GuestConditionAcknowledgmentDTO();
            dto.setBookingId(1L);
            dto.setConditionAccepted(true);

            // Act
            CheckInStatusDTO result = checkInService.acknowledgeCondition(dto, 200L);

            // Assert: returns a result (not null/error)
            assertThat(result).isNotNull();

            // Assert: NO events recorded (idempotent - no side effects)
            verify(eventService, never()).recordEvent(
                    any(), any(), any(), any(), any(), any());
            verify(eventService, never()).recordEvent(
                    any(), any(), any(), any(), any(), any(Instant.class), any());

            // Assert: booking NOT saved again
            verify(bookingRepository, never()).save(any(Booking.class));
        }

        @Test
        @DisplayName("R5: Acknowledgment after IN_TRIP returns cached result")
        void shouldReturnCachedResultAfterTripStarted() {
            booking.setStatus(BookingStatus.IN_TRIP);
            booking.setGuestCheckInCompletedAt(Instant.now().minusSeconds(300));

            when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));

            GuestConditionAcknowledgmentDTO dto = new GuestConditionAcknowledgmentDTO();
            dto.setBookingId(1L);
            dto.setConditionAccepted(true);

            CheckInStatusDTO result = checkInService.acknowledgeCondition(dto, 200L);

            assertThat(result).isNotNull();

            // No state mutations
            verify(eventService, never()).recordEvent(
                    any(), any(), any(), any(), any(), any());
            verify(bookingRepository, never()).save(any(Booking.class));
        }

        @Test
        @DisplayName("R5: First acknowledgment proceeds normally (sets guestCheckInCompletedAt)")
        void shouldProcessFirstAcknowledgmentNormally() {
            // Arrange: booking ready for guest acknowledgment
            booking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
            booking.setGuestCheckInCompletedAt(null); // Not yet completed

            when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));

            GuestConditionAcknowledgmentDTO dto = new GuestConditionAcknowledgmentDTO();
            dto.setBookingId(1L);
            dto.setConditionAccepted(true);

            // Act
            CheckInStatusDTO result = checkInService.acknowledgeCondition(dto, 200L);

            // Assert: result returned
            assertThat(result).isNotNull();

            // Assert: booking was saved with the new status
            verify(bookingRepository).save(any(Booking.class));

            // Assert: guestCheckInCompletedAt was set
            assertThat(booking.getGuestCheckInCompletedAt()).isNotNull();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CHECK_IN_COMPLETE);

            // Assert: events were recorded
            verify(eventService, atLeastOnce()).recordEvent(
                    any(), any(), any(), any(), any(), any());
        }
    }

    // ========================================================================
    // Handshake Idempotency (pre-existing, verify still works)
    // ========================================================================

    @Nested
    @DisplayName("Handshake Idempotency (regression)")
    class HandshakeIdempotencyRegressionTests {

        @Test
        @DisplayName("Concurrent handshake when already IN_TRIP returns cached (no duplicate transition)")
        void shouldReturnCachedResultForDuplicateHandshake() {
            booking.setStatus(BookingStatus.IN_TRIP);
            booking.setHandshakeCompletedAt(Instant.now().minusSeconds(30));
            booking.setTripStartedAt(Instant.now().minusSeconds(30));

            when(bookingRepository.findByIdWithLock(1L)).thenReturn(Optional.of(booking));

            HandshakeConfirmationDTO dto = new HandshakeConfirmationDTO();
            dto.setBookingId(1L);
            dto.setConfirmed(true);

            CheckInStatusDTO result = checkInService.confirmHandshake(dto, 200L);

            assertThat(result).isNotNull();

            // No status change occurred
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.IN_TRIP);

            // No events recorded (idempotent)
            verify(eventService, never()).recordEvent(
                    any(), any(), any(), any(), any(), any());
        }
    }
}
