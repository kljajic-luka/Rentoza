package org.example.rentoza.booking.checkin;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.RentalAgreementService;
import org.example.rentoza.booking.checkin.dto.CheckInStatusDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.config.FeatureFlags;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.booking.photo.PhotoUrlService;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckInStatusReadModelTest {

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
    @Mock private PhotoUrlService photoUrlService;
    @Mock private BookingPaymentService bookingPaymentService;
    @Mock private RentalAgreementService rentalAgreementService;
    @Mock private CheckInAttestationService checkInAttestationService;

    private CheckInService checkInService;

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

        when(featureFlags.isDualPartyPhotosEnabledForBooking(1L)).thenReturn(false);
        when(featureFlags.isDualPartyPhotosRequiredForHandshake()).thenReturn(false);
        when(validationService.getMaxEarlyCheckInHours()).thenReturn(1);
        when(geofenceService.getDefaultRadiusMeters()).thenReturn(500);
    }

    @Test
    @DisplayName("getCheckInStatus returns full vehicle photo payload after host completion")
    void getCheckInStatus_returnsFullVehiclePhotoPayload_afterHostCompletion() {
        User renter = new User();
        renter.setId(10L);
        User owner = new User();
        owner.setId(20L);

        Car car = new Car();
        car.setId(100L);
        car.setBrand("Fiat");
        car.setModel("Panda");
        car.setYear(2022);
        car.setOwner(owner);

        Booking booking = new Booking();
        booking.setId(1L);
        booking.setStatus(BookingStatus.CHECK_IN_HOST_COMPLETE);
        booking.setRenter(renter);
        booking.setCar(car);
        booking.setCheckInSessionId("session-1");
        booking.setHostCheckInCompletedAt(Instant.now());
        booking.setCheckInOpenedAt(Instant.now().minusSeconds(600));
        booking.setStartTime(LocalDateTime.now().plusMinutes(30));
        booking.setEndTime(LocalDateTime.now().plusDays(2));
        booking.setLockboxCodeEncrypted("encrypted".getBytes(StandardCharsets.UTF_8));
        booking.setStartOdometer(12345);
        booking.setStartFuelLevel(75);

        CheckInPhoto photo = CheckInPhoto.builder()
                .id(501L)
                .booking(booking)
                .checkInSessionId("session-1")
                .photoType(CheckInPhotoType.HOST_EXTERIOR_FRONT)
                .storageBucket(CheckInPhoto.StorageBucket.CHECKIN_STANDARD)
                .storageKey("checkin/session-1/front.jpg")
                .mimeType("image/jpeg")
                .fileSizeBytes(1024)
                .uploadedAt(Instant.now())
                .exifValidationStatus(ExifValidationStatus.VALID)
                .build();

        when(bookingRepository.findByIdWithRelations(1L)).thenReturn(Optional.of(booking));
        when(photoRepository.findByCheckInSessionId("session-1")).thenReturn(List.of(photo));
        when(photoUrlService.generateSignedUrl("check-in-photos", "checkin/session-1/front.jpg", 501L))
                .thenReturn("https://signed.example/front.jpg");

        CheckInStatusDTO status = checkInService.getCheckInStatus(1L, 10L);

        assertThat(status.getVehiclePhotos()).isNotNull();
        assertThat(status.getVehiclePhotos()).hasSize(1);
        assertThat(status.getVehiclePhotos().get(0).getUrl()).isEqualTo("https://signed.example/front.jpg");
        assertThat(status.getVehiclePhotos().get(0).getPhotoType()).isEqualTo(CheckInPhotoType.HOST_EXTERIOR_FRONT);
        assertThat(status.isCanGuestAcknowledge()).isTrue();
        assertThat(status.isHandshakeReady()).isFalse();
    }
}