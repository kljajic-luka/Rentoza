package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.RentalAgreementService;
import org.example.rentoza.booking.checkin.dto.HandshakeConfirmationDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.config.FeatureFlags;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RentalAgreementEnforcementTest {

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
                rentalAgreementService
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
    }

    @Test
    @DisplayName("confirmHandshake_enforcementEnabledAndAgreementMissing_throwsSerbianMessage")
    void confirmHandshake_enforcementEnabledAndAgreementMissing_throwsSerbianMessage() {
        when(featureFlags.isRentalAgreementCheckinEnforced()).thenReturn(true);
        when(rentalAgreementService.isFullyAccepted(1L)).thenReturn(false);

        assertThatThrownBy(() -> checkInService.confirmHandshake(dto, 20L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Ugovor o iznajmljivanju mora biti prihvaćen od obe strane pre početka vožnje. " +
                        "Otvorite detalje rezervacije i prihvatite ugovor.");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CHECK_IN_COMPLETE);
    }

    @Test
    @DisplayName("confirmHandshake_enforcementEnabledAndAgreementPartial_throwsAndKeepsStatus")
    void confirmHandshake_enforcementEnabledAndAgreementPartial_throwsAndKeepsStatus() {
        when(featureFlags.isRentalAgreementCheckinEnforced()).thenReturn(true);
        when(rentalAgreementService.isFullyAccepted(1L)).thenReturn(false);

        assertThatThrownBy(() -> checkInService.confirmHandshake(dto, 20L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ugovor o iznajmljivanju mora biti prihvaćen od obe strane");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CHECK_IN_COMPLETE);
    }

    @Test
    @DisplayName("confirmHandshake_enforcementEnabledAndAgreementAccepted_startsTrip")
    void confirmHandshake_enforcementEnabledAndAgreementAccepted_startsTrip() {
        when(featureFlags.isRentalAgreementCheckinEnforced()).thenReturn(true);
        when(rentalAgreementService.isFullyAccepted(1L)).thenReturn(true);
        makeFinanciallyCollectible();

        when(bookingPaymentService.captureBookingPaymentNow(anyLong()))
                .thenReturn(PaymentProvider.PaymentResult.builder()
                        .success(true)
                        .status(PaymentProvider.PaymentStatus.SUCCESS)
                        .build());

        checkInService.confirmHandshake(dto, 20L);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.IN_TRIP);
    }

    @Test
    @DisplayName("confirmHandshake_enforcementDisabledAndAgreementMissing_startsTrip")
    void confirmHandshake_enforcementDisabledAndAgreementMissing_startsTrip() {
        when(featureFlags.isRentalAgreementCheckinEnforced()).thenReturn(false);
        when(rentalAgreementService.isFullyAccepted(1L)).thenReturn(false);
        makeFinanciallyCollectible();

        when(bookingPaymentService.captureBookingPaymentNow(anyLong()))
                .thenReturn(PaymentProvider.PaymentResult.builder()
                        .success(true)
                        .status(PaymentProvider.PaymentStatus.SUCCESS)
                        .build());

        assertThatCode(() -> checkInService.confirmHandshake(dto, 20L))
                .doesNotThrowAnyException();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.IN_TRIP);
    }

    @Test
    @DisplayName("confirmHandshake_enforcementDisabledAndAgreementPartial_startsTrip")
    void confirmHandshake_enforcementDisabledAndAgreementPartial_startsTrip() {
        when(featureFlags.isRentalAgreementCheckinEnforced()).thenReturn(false);
        when(rentalAgreementService.isFullyAccepted(1L)).thenReturn(false);
        makeFinanciallyCollectible();

        when(bookingPaymentService.captureBookingPaymentNow(anyLong()))
                .thenReturn(PaymentProvider.PaymentResult.builder()
                        .success(true)
                        .status(PaymentProvider.PaymentStatus.SUCCESS)
                        .build());

        assertThatCode(() -> checkInService.confirmHandshake(dto, 20L))
                .doesNotThrowAnyException();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.IN_TRIP);
    }

    private void makeFinanciallyCollectible() {
        booking.setChargeLifecycleStatus(ChargeLifecycleStatus.AUTHORIZED);
        booking.setBookingAuthorizationId("auth-1");
        booking.setBookingAuthExpiresAt(Instant.now().plusSeconds(3600));
        booking.setDepositLifecycleStatus(DepositLifecycleStatus.AUTHORIZED);
        booking.setDepositAuthorizationId("dep-auth");
        booking.setDepositAuthExpiresAt(Instant.now().plusSeconds(3600));
    }
}