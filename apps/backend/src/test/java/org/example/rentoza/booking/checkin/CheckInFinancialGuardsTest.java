package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.RentalAgreementService;
import org.example.rentoza.booking.checkin.dto.HandshakeConfirmationDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.config.FeatureFlags;
import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.payment.ChargeLifecycleStatus;
import org.example.rentoza.payment.DepositLifecycleStatus;
import org.example.rentoza.payment.PaymentProvider;
import org.example.rentoza.security.LockboxEncryptionService;
import org.example.rentoza.user.RenterVerificationService;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckInFinancialGuardsTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private CheckInEventService eventService;
    @Mock private CheckInPhotoRepository photoRepository;
    @Mock private GuestCheckInPhotoRepository guestCheckInPhotoRepository;
    @Mock private GeofenceService geofenceService;
    @Mock private NotificationService notificationService;
    @Mock private LockboxEncryptionService lockboxEncryptionService;
    @Mock private RenterVerificationService renterVerificationService;
    @Mock private FeatureFlags featureFlags;
    @Mock private CheckInValidationService validationService;
    @Mock private org.example.rentoza.booking.dispute.DamageClaimRepository damageClaimRepository;
    @Mock private org.example.rentoza.user.UserRepository userRepository;
    @Mock private org.example.rentoza.booking.photo.PhotoUrlService photoUrlService;
    @Mock private BookingPaymentService bookingPaymentService;
    @Mock private RentalAgreementService rentalAgreementService;
    @Mock private CheckInAttestationService checkInAttestationService;

    private CheckInService checkInService;
    private Booking booking;
    private HandshakeConfirmationDTO dto;

    @BeforeEach
    void setUp() {
        checkInService = new CheckInService(
                bookingRepository,
                eventService,
                photoRepository,
                guestCheckInPhotoRepository,
                geofenceService,
                notificationService,
                lockboxEncryptionService,
                renterVerificationService,
                featureFlags,
                validationService,
                damageClaimRepository,
                userRepository,
                new SimpleMeterRegistry(),
                photoUrlService,
                bookingPaymentService,
                rentalAgreementService,
                checkInAttestationService
        );

        User renter = new User();
        renter.setId(10L);
        User owner = new User();
        owner.setId(20L);

        Car car = new Car();
        car.setId(100L);
        car.setOwner(owner);

        booking = new Booking();
        booking.setId(1L);
        booking.setStatus(BookingStatus.CHECK_IN_COMPLETE);
        booking.setRenter(renter);
        booking.setCar(car);
        booking.setLockboxCodeEncrypted("encrypted".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        booking.setStartTime(LocalDateTime.now().minusMinutes(10));
        booking.setEndTime(LocalDateTime.now().plusDays(2));
        booking.setCheckInSessionId("session-1");
        booking.setTotalPrice(new BigDecimal("10000.00"));
        booking.setSecurityDeposit(new BigDecimal("30000.00"));

        dto = new HandshakeConfirmationDTO();
        dto.setBookingId(1L);
        dto.setConfirmed(true);

        when(bookingRepository.findByIdWithLock(1L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_HOST_CONFIRMED)).thenReturn(true);
        when(eventService.hasEventOfType(1L, CheckInEventType.HANDSHAKE_GUEST_CONFIRMED)).thenReturn(true);
        when(featureFlags.isStrictCheckinEnabled()).thenReturn(false);
        when(featureFlags.isRentalAgreementCheckinEnforced()).thenReturn(true);
        when(rentalAgreementService.isFullyAccepted(1L)).thenReturn(true);
    }

    @Test
    @DisplayName("Blocks handshake when booking is REAUTH_REQUIRED")
    void blocks_when_reauth_required() {
        booking.setChargeLifecycleStatus(ChargeLifecycleStatus.REAUTH_REQUIRED);
        booking.setBookingAuthExpiresAt(Instant.now().plusSeconds(3600));
        booking.setDepositLifecycleStatus(DepositLifecycleStatus.AUTHORIZED);
        booking.setDepositAuthorizationId("dep-auth");

        assertThatThrownBy(() -> checkInService.confirmHandshake(dto, 20L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorization is not collectible");
    }

    @Test
    @DisplayName("Blocks handshake on expired authorization")
    void blocks_on_expired_auth() {
        booking.setChargeLifecycleStatus(ChargeLifecycleStatus.AUTHORIZED);
        booking.setBookingAuthorizationId("auth-1");
        booking.setBookingAuthExpiresAt(Instant.now().minusSeconds(30));
        booking.setDepositLifecycleStatus(DepositLifecycleStatus.AUTHORIZED);
        booking.setDepositAuthorizationId("dep-auth");

        assertThatThrownBy(() -> checkInService.confirmHandshake(dto, 20L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorization has expired");
    }

    @Test
    @DisplayName("Blocks handshake when deposit authorization missing")
    void blocks_on_missing_deposit_auth() {
        booking.setChargeLifecycleStatus(ChargeLifecycleStatus.AUTHORIZED);
        booking.setBookingAuthorizationId("auth-1");
        booking.setBookingAuthExpiresAt(Instant.now().plusSeconds(3600));
        booking.setDepositLifecycleStatus(DepositLifecycleStatus.PENDING);

        assertThatThrownBy(() -> checkInService.confirmHandshake(dto, 20L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security deposit authorization is required");
    }

    @Test
    @DisplayName("Starts trip only when capture succeeds")
    void starts_trip_when_capture_succeeds() {
        booking.setChargeLifecycleStatus(ChargeLifecycleStatus.AUTHORIZED);
        booking.setBookingAuthorizationId("auth-1");
        booking.setBookingAuthExpiresAt(Instant.now().plusSeconds(3600));
        booking.setDepositLifecycleStatus(DepositLifecycleStatus.AUTHORIZED);
        booking.setDepositAuthorizationId("dep-auth");
        booking.setDepositAuthExpiresAt(Instant.now().plusSeconds(3600));

        when(bookingPaymentService.captureBookingPaymentNow(anyLong()))
                .thenReturn(PaymentProvider.PaymentResult.builder()
                        .success(true)
                        .status(PaymentProvider.PaymentStatus.SUCCESS)
                        .build());

        checkInService.confirmHandshake(dto, 20L);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.IN_TRIP);
        verify(checkInAttestationService).requestTripStartAttestation(1L, "session-1", 20L);
        verify(checkInAttestationService, never()).generateTripStartAttestation(any(Booking.class), anyLong());
    }

    @Test
    @DisplayName("Capture failure keeps booking out of IN_TRIP")
    void capture_failure_blocks_trip_start() {
        booking.setChargeLifecycleStatus(ChargeLifecycleStatus.AUTHORIZED);
        booking.setBookingAuthorizationId("auth-1");
        booking.setBookingAuthExpiresAt(Instant.now().plusSeconds(3600));
        booking.setDepositLifecycleStatus(DepositLifecycleStatus.AUTHORIZED);
        booking.setDepositAuthorizationId("dep-auth");
        booking.setDepositAuthExpiresAt(Instant.now().plusSeconds(3600));

        when(bookingPaymentService.captureBookingPaymentNow(anyLong()))
                .thenReturn(PaymentProvider.PaymentResult.builder()
                        .success(false)
                        .status(PaymentProvider.PaymentStatus.FAILED)
                        .errorMessage("declined")
                        .build());

        assertThatThrownBy(() -> checkInService.confirmHandshake(dto, 20L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Naplata rezervacije nije uspela");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CHECK_IN_COMPLETE);
    }

    @Test
    @DisplayName("Notification errors after capture do not roll back IN_TRIP")
    void notification_failure_does_not_rollback_trip_start() {
        booking.setChargeLifecycleStatus(ChargeLifecycleStatus.AUTHORIZED);
        booking.setBookingAuthorizationId("auth-1");
        booking.setBookingAuthExpiresAt(Instant.now().plusSeconds(3600));
        booking.setDepositLifecycleStatus(DepositLifecycleStatus.AUTHORIZED);
        booking.setDepositAuthorizationId("dep-auth");
        booking.setDepositAuthExpiresAt(Instant.now().plusSeconds(3600));

        when(bookingPaymentService.captureBookingPaymentNow(anyLong()))
                .thenReturn(PaymentProvider.PaymentResult.builder()
                        .success(true)
                        .status(PaymentProvider.PaymentStatus.SUCCESS)
                        .build());
        doThrow(new RuntimeException("push failed")).when(notificationService).createNotification(any());

        checkInService.confirmHandshake(dto, 20L);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.IN_TRIP);
        verify(bookingRepository).save(booking);
    }

    @Test
    @DisplayName("Handshake blocks when only a prior-session guest photo set exists")
    void prior_session_guest_photos_do_not_satisfy_current_handshake() {
        booking.setChargeLifecycleStatus(ChargeLifecycleStatus.AUTHORIZED);
        booking.setBookingAuthorizationId("auth-1");
        booking.setBookingAuthExpiresAt(Instant.now().plusSeconds(3600));
        booking.setDepositLifecycleStatus(DepositLifecycleStatus.AUTHORIZED);
        booking.setDepositAuthorizationId("dep-auth");
        booking.setDepositAuthExpiresAt(Instant.now().plusSeconds(3600));

        when(featureFlags.isDualPartyPhotosEnabledForBooking(1L)).thenReturn(true);
        when(featureFlags.isDualPartyPhotosRequiredForHandshake()).thenReturn(true);
        when(guestCheckInPhotoRepository.countRequiredGuestPhotoTypesBySession("session-1")).thenReturn(0L);

        assertThatThrownBy(() -> checkInService.confirmHandshake(dto, 10L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Morate otpremiti sve obavezne fotografije");

        verify(guestCheckInPhotoRepository).countRequiredGuestPhotoTypesBySession("session-1");
    }
}
