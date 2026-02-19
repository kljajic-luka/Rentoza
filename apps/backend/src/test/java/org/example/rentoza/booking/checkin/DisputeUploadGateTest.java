package org.example.rentoza.booking.checkin;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.ExifValidationService.ExifValidationResult;
import org.example.rentoza.booking.photo.PiiPhotoStorageService;
import org.example.rentoza.car.Car;
import org.example.rentoza.security.LockboxEncryptionService;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.util.ExifStrippingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused tests for the dispute-state upload gate in CheckInPhotoService.
 *
 * Critical scenario:
 * - Booking in CHECKOUT_DAMAGE_DISPUTE
 * - CHECKOUT_DAMAGE_NEW / CHECKOUT_CUSTOM as guest evidence -> must succeed past gate
 * - Non-evidence guest checkout types (e.g., CHECKOUT_EXTERIOR_FRONT) -> must fail
 *
 * @since Feature 9 - Damage Claims & Disputes
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CheckInPhotoService - Dispute Upload Gate")
class DisputeUploadGateTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private UserRepository userRepository;
    @Mock private CheckInPhotoRepository photoRepository;
    @Mock private CheckInEventService eventService;
    @Mock private ExifValidationService exifValidationService;
    @Mock private LockboxEncryptionService lockboxEncryptionService;
    @Mock private GeofenceService geofenceService;
    @Mock private PhotoRejectionService photoRejectionService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SupabaseStorageService supabaseStorageService;
    @Mock private PiiPhotoStorageService piiPhotoStorageService;
    @Mock private ExifStrippingService exifStrippingService;
    @Mock private CheckInValidationService validationService;

    private CheckInPhotoService service;

    // Test fixtures
    private Booking booking;
    private User guest;
    private MockMultipartFile validJpeg;

    // JPEG magic bytes: FF D8 FF E0 ... (minimum 12 bytes for signature validation)
    private static final byte[] JPEG_MAGIC = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            // Padding to make a non-empty file
            0x00, 0x00, 0x00, 0x00
    };

    @BeforeEach
    void setUp() {
        service = new CheckInPhotoService(
                bookingRepository,
                userRepository,
                photoRepository,
                eventService,
                exifValidationService,
                lockboxEncryptionService,
                geofenceService,
                photoRejectionService,
                eventPublisher,
                supabaseStorageService,
                piiPhotoStorageService,
                exifStrippingService,
                validationService
        );
        ReflectionTestUtils.setField(service, "maxSizeMb", 10);

        // Guest user
        guest = new User();
        guest.setId(2L);
        guest.setEmail("guest@test.com");

        // Host user
        User host = new User();
        host.setId(1L);
        host.setEmail("host@test.com");

        // Car
        Car car = new Car();
        car.setId(100L);
        car.setOwner(host);

        // Booking in CHECKOUT_DAMAGE_DISPUTE
        booking = new Booking();
        booking.setId(1000L);
        booking.setCar(car);
        booking.setRenter(guest);
        booking.setStatus(BookingStatus.CHECKOUT_DAMAGE_DISPUTE);

        when(bookingRepository.findByIdWithRelations(1000L)).thenReturn(Optional.of(booking));

        // Valid JPEG file with proper magic bytes
        validJpeg = new MockMultipartFile(
                "photo", "evidence.jpg", "image/jpeg", JPEG_MAGIC);
    }

    /** Stub post-gate dependencies so the upload progresses past the status gate. */
    private void stubPostGateDependencies() {
        when(photoRepository.countByBookingId(1000L)).thenReturn(0L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(guest));
        when(exifValidationService.validate(any(byte[].class), any()))
                .thenReturn(ExifValidationResult.builder()
                        .status(ExifValidationStatus.VALID)
                        .message("ok")
                        .build());
    }

    @Nested
    @DisplayName("Evidence uploads in CHECKOUT_DAMAGE_DISPUTE state")
    class EvidenceUploadsAllowed {

        @Test
        @DisplayName("CHECKOUT_DAMAGE_NEW succeeds past upload gate in dispute state")
        void checkoutDamageNewShouldPassGate() {
            stubPostGateDependencies();

            // We only care that IllegalStateException is NOT thrown at the gate.
            // Further failures (storage, null pointer, etc.) are fine - they prove the gate passed.
            try {
                service.uploadPhoto(
                        1000L, 2L, validJpeg,
                        CheckInPhotoType.CHECKOUT_DAMAGE_NEW,
                        Instant.now(),
                        BigDecimal.valueOf(44.8), BigDecimal.valueOf(20.4));
            } catch (IllegalStateException e) {
                fail("Upload gate should NOT block CHECKOUT_DAMAGE_NEW in dispute state, but threw: " + e.getMessage());
            } catch (Exception e) {
                // Other exceptions (storage, etc.) are expected -
                // they prove the status gate was passed successfully.
            }
        }

        @Test
        @DisplayName("CHECKOUT_CUSTOM succeeds past upload gate in dispute state")
        void checkoutCustomShouldPassGate() {
            stubPostGateDependencies();

            try {
                service.uploadPhoto(
                        1000L, 2L, validJpeg,
                        CheckInPhotoType.CHECKOUT_CUSTOM,
                        Instant.now(),
                        BigDecimal.valueOf(44.8), BigDecimal.valueOf(20.4));
            } catch (IllegalStateException e) {
                fail("Upload gate should NOT block CHECKOUT_CUSTOM in dispute state, but threw: " + e.getMessage());
            } catch (Exception e) {
                // Other exceptions are expected - they prove the status gate was passed.
            }
        }
    }

    @Nested
    @DisplayName("Non-evidence uploads blocked in CHECKOUT_DAMAGE_DISPUTE state")
    class NonEvidenceUploadsBlocked {

        @Test
        @DisplayName("CHECKOUT_EXTERIOR_FRONT fails in dispute state")
        void checkoutExteriorFrontBlockedInDisputeState() {
            assertThatThrownBy(() -> service.uploadPhoto(
                    1000L, 2L, validJpeg,
                    CheckInPhotoType.CHECKOUT_EXTERIOR_FRONT,
                    Instant.now(),
                    BigDecimal.valueOf(44.8), BigDecimal.valueOf(20.4)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("otvoren");
        }

        @Test
        @DisplayName("CHECKOUT_ODOMETER fails in dispute state")
        void checkoutOdometerBlockedInDisputeState() {
            assertThatThrownBy(() -> service.uploadPhoto(
                    1000L, 2L, validJpeg,
                    CheckInPhotoType.CHECKOUT_ODOMETER,
                    Instant.now(),
                    BigDecimal.valueOf(44.8), BigDecimal.valueOf(20.4)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("otvoren");
        }

        @Test
        @DisplayName("CHECKOUT_FUEL_GAUGE fails in dispute state")
        void checkoutFuelGaugeBlockedInDisputeState() {
            assertThatThrownBy(() -> service.uploadPhoto(
                    1000L, 2L, validJpeg,
                    CheckInPhotoType.CHECKOUT_FUEL_GAUGE,
                    Instant.now(),
                    BigDecimal.valueOf(44.8), BigDecimal.valueOf(20.4)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("otvoren");
        }

        @Test
        @DisplayName("CHECKOUT_INTERIOR_DASHBOARD fails in dispute state")
        void checkoutInteriorDashboardBlockedInDisputeState() {
            assertThatThrownBy(() -> service.uploadPhoto(
                    1000L, 2L, validJpeg,
                    CheckInPhotoType.CHECKOUT_INTERIOR_DASHBOARD,
                    Instant.now(),
                    BigDecimal.valueOf(44.8), BigDecimal.valueOf(20.4)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("otvoren");
        }
    }

    @Nested
    @DisplayName("Normal checkout flow still works")
    class NormalCheckoutFlow {

        @Test
        @DisplayName("Guest uploads pass gate in CHECKOUT_OPEN state (all types)")
        void guestUploadPassesInCheckoutOpenState() {
            booking.setStatus(BookingStatus.CHECKOUT_OPEN);
            stubPostGateDependencies();

            // CHECKOUT_EXTERIOR_FRONT should pass in normal CHECKOUT_OPEN
            try {
                service.uploadPhoto(
                        1000L, 2L, validJpeg,
                        CheckInPhotoType.CHECKOUT_EXTERIOR_FRONT,
                        Instant.now(),
                        BigDecimal.valueOf(44.8), BigDecimal.valueOf(20.4));
            } catch (IllegalStateException e) {
                fail("Upload gate should NOT block in CHECKOUT_OPEN state, but threw: " + e.getMessage());
            } catch (Exception e) {
                // Other exceptions are expected - they prove the status gate was passed.
            }
        }
    }
}
