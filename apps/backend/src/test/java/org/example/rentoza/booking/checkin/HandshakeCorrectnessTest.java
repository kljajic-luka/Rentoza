package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.dto.CheckInStatusDTO;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Handshake Correctness: idempotency guard, DTO fields, trip-start invariants")
class HandshakeCorrectnessTest {

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

    private CheckInService checkInService;
    private Booking booking;
    private User host;
    private User renter;

    @BeforeEach
    void setUp() {
        checkInService = new CheckInService(
                bookingRepository, eventService, photoRepository,
                guestPhotoRepository, geofenceService, notificationService,
                lockboxEncryptionService, renterVerificationService,
                featureFlags, validationService, damageClaimRepository,
                userRepository, new SimpleMeterRegistry(),
                photoUrlService, bookingPaymentService,
                rentalAgreementService
        );
        // Disable @Value-injected license verification so trip-start tests don't require it
        ReflectionTestUtils.setField(checkInService, "licenseVerificationEnabled", false);
        ReflectionTestUtils.setField(checkInService, "licenseVerificationRequired", false);
        ReflectionTestUtils.setField(checkInService, "noShowGraceMinutes", 30);

        host = new User(); host.setId(100L);
        renter = new User(); renter.setId(200L);
        Car car = new Car(); car.setId(1L); car.setOwner(host);

        booking = new Booking();
        booking.setId(1L);
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setCheckInSessionId("session-1");
        booking.setCheckInEvents(new ArrayList<>());
        booking.setCheckInPhotos(new ArrayList<>());
        booking.setStatus(BookingStatus.CHECK_IN_COMPLETE);

        // Common stubs
        when(bookingRepository.findByIdWithLock(1L)).thenReturn(Optional.of(booking));
        when(photoRepository.findByBookingId(1L)).thenReturn(List.of());
        when(geofenceService.getDefaultRadiusMeters()).thenReturn(100);
        when(featureFlags.isDualPartyPhotosRequiredForHandshake()).thenReturn(false);
        when(featureFlags.isStrictCheckinEnabled()).thenReturn(false);
    }

    // ---- helper ----
    private HandshakeConfirmationDTO dto(Long bookingId, boolean confirmed) {
        HandshakeConfirmationDTO dto = new HandshakeConfirmationDTO();
        dto.setBookingId(bookingId);
        dto.setConfirmed(confirmed);
        return dto;
    }

    // ---- T1: host-only confirm records exactly one HOST event, status stays CHECK_IN_COMPLETE ----

    @Test
    @DisplayName("T1: host-only confirm → exactly one HOST event, no TRIP_STARTED, status stays CHECK_IN_COMPLETE")
    void hostOnlyConfirm_recordsOneHostEvent_noTripStart() {
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_HOST_CONFIRMED))
                .thenReturn(false, true); // false on guard check, true after recording
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_GUEST_CONFIRMED))
                .thenReturn(false);

        CheckInStatusDTO result = checkInService.confirmHandshake(dto(1L, true), 100L);

        assertThat(result).isNotNull();
        // Exactly one HOST event recorded
        verify(eventService, times(1)).recordEvent(
                any(), any(), eq(CheckInEventType.HANDSHAKE_HOST_CONFIRMED),
                eq(100L), eq(CheckInActorRole.HOST), any());
        // No TRIP_STARTED
        verify(eventService, never()).recordEvent(
                any(), any(), eq(CheckInEventType.TRIP_STARTED), any(), any(), any());
        // Booking still CHECK_IN_COMPLETE (not IN_TRIP)
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CHECK_IN_COMPLETE);
    }

    // ---- T2: host retry → no duplicate HOST event ----

    @Test
    @DisplayName("T2: host retry when already confirmed → zero additional HOST events")
    void hostRetry_whenAlreadyConfirmed_noAdditionalEvent() {
        // Host already confirmed (simulate prior confirmation in DB)
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_HOST_CONFIRMED))
                .thenReturn(true);
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_GUEST_CONFIRMED))
                .thenReturn(false);

        checkInService.confirmHandshake(dto(1L, true), 100L);

        // No HOST event should be recorded on a retry
        verify(eventService, never()).recordEvent(
                any(), any(), eq(CheckInEventType.HANDSHAKE_HOST_CONFIRMED),
                any(), any(), any());
        verify(eventService, never()).recordEvent(
            any(), any(), eq(CheckInEventType.TRIP_STARTED), any(), any(), any(Map.class));
    }

    // ---- T3: host already confirmed → guest confirms → trip starts exactly once ----

    @Test
    @DisplayName("T3: host pre-confirmed, guest confirms → TRIP_STARTED exactly once, status = IN_TRIP")
    void guestConfirmsAfterHost_tripStartsOnce() {
        // Host is already confirmed; guest is not yet confirmed
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_HOST_CONFIRMED))
                .thenReturn(true);
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_GUEST_CONFIRMED))
                .thenReturn(false, true); // false on guard check, true on trip-start check

        CheckInStatusDTO result = checkInService.confirmHandshake(dto(1L, true), 200L); // guest

        assertThat(result).isNotNull();
        verify(eventService, times(1)).recordEvent(
                any(), any(), eq(CheckInEventType.HANDSHAKE_GUEST_CONFIRMED),
                eq(200L), eq(CheckInActorRole.GUEST), any());
        verify(eventService, times(1)).recordEvent(
                any(), any(), eq(CheckInEventType.TRIP_STARTED), any(), any(), any());
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.IN_TRIP);
    }

    // ---- T4: guest already confirmed → host confirms → trip starts exactly once ----

    @Test
    @DisplayName("T4: guest pre-confirmed, host confirms → TRIP_STARTED exactly once, status = IN_TRIP")
    void hostConfirmsAfterGuest_tripStartsOnce() {
        // Guest is already confirmed; host is not yet confirmed
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_HOST_CONFIRMED))
                .thenReturn(false, true); // false on guard check, true on trip-start check
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_GUEST_CONFIRMED))
                .thenReturn(true);

        CheckInStatusDTO result = checkInService.confirmHandshake(dto(1L, true), 100L); // host

        assertThat(result).isNotNull();
        verify(eventService, times(1)).recordEvent(
                any(), any(), eq(CheckInEventType.HANDSHAKE_HOST_CONFIRMED),
                eq(100L), eq(CheckInActorRole.HOST), any());
        verify(eventService, times(1)).recordEvent(
                any(), any(), eq(CheckInEventType.TRIP_STARTED), any(), any(), any());
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.IN_TRIP);
    }

    // ---- T5: mapToStatusDTO populates per-party handshake fields after host-only confirm ----

    @Test
    @DisplayName("T5: after host-only confirm, DTO has hostConfirmedHandshake=true, guestConfirmedHandshake=false, handshakeComplete=false, canStartTrip=true")
    void dtoFields_afterHostOnlyConfirm() {
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_HOST_CONFIRMED))
                .thenReturn(false, true, true); // guard=false → record; trip-check=true; mapToStatusDTO=true
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_GUEST_CONFIRMED))
                .thenReturn(false, false); // trip-check=false; mapToStatusDTO=false

        CheckInStatusDTO result = checkInService.confirmHandshake(dto(1L, true), 100L);

        assertThat(result.isHostConfirmedHandshake()).isTrue();
        assertThat(result.isGuestConfirmedHandshake()).isFalse();
        assertThat(result.isHandshakeComplete()).isFalse();
        assertThat(result.isCanStartTrip()).isTrue();
        assertThat(result.isGuestConditionAcknowledged()).isFalse();
        assertThat(result.getLastUpdated()).isNotNull();
    }
}
