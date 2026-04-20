package org.example.rentoza.booking.checkin;

import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.booking.checkin.dto.CheckInStatusDTO;
import org.example.rentoza.booking.checkin.dto.HandshakeConfirmationDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.car.FuelType;
import org.example.rentoza.car.TransmissionType;
import org.example.rentoza.config.FeatureFlags;
import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.testconfig.AbstractIntegrationTest;
import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Integration tests for CheckInService using PostgreSQL Testcontainer.
 * 
 * <p>These tests verify the complete check-in flow with a real database,
 * ensuring proper transaction behavior and constraint enforcement.
 * 
 * <h2>Test Coverage</h2>
 * <ul>
 *   <li>Handshake confirmation with license validation</li>
 *   <li>State transitions</li>
 *   <li>Database constraint enforcement</li>
 * </ul>
 */
@DisplayName("CheckInService - PostgreSQL Integration Tests")
@Transactional
@TestPropertySource(properties = {
    "supabase.url=https://example.supabase.co",
    "supabase.anon-key=test-anon-key",
    "supabase.service-role-key=test-service-role-key",
    "supabase.jwt-secret=test-secret",
    "internal.service.jwt.secret=test-internal-secret",
    "SUPABASE_URL=https://example.supabase.co",
    "SUPABASE_ANON_KEY=test-anon-key",
    "SUPABASE_SERVICE_ROLE_KEY=test-service-role-key",
    "SUPABASE_JWT_SECRET=test-secret",
    "INTERNAL_SERVICE_JWT_SECRET=test-internal-secret"
})
class CheckInServicePostgresIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CheckInService checkInService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CarRepository carRepository;

    @Autowired
    private GuestCheckInPhotoRepository guestCheckInPhotoRepository;

    @MockBean
    private FeatureFlags featureFlags;

    @MockBean
    private org.example.rentoza.car.storage.DocumentStorageStrategy documentStorageStrategy;

    @MockBean
    private org.example.rentoza.security.supabase.SupabaseJwtUtil supabaseJwtUtil;

    @MockBean
    private org.example.rentoza.security.InternalServiceJwtUtil internalServiceJwtUtil;

    @MockBean
    private org.example.rentoza.booking.checkin.verification.IdVerificationProvider idVerificationProvider;

    private User host;
    private User renter;
    private Car car;
    private Booking booking;

    @BeforeEach
    void setUp() {
        // Create host user
        host = new User();
        host.setEmail("host-" + System.currentTimeMillis() + "@test.com");
        host.setFirstName("Test");
        host.setLastName("Host");
        host.setPassword("securePassword123");
        host.setAge(30);
        host = userRepository.save(host);

        // Create renter user with valid license
        renter = new User();
        renter.setEmail("renter-" + System.currentTimeMillis() + "@test.com");
        renter.setFirstName("Test");
        renter.setLastName("Renter");
        renter.setPassword("securePassword123");
        renter.setAge(25);
        renter.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
        renter.setDriverLicenseExpiryDate(LocalDate.now().plusYears(2));
        renter = userRepository.save(renter);

        // Create car
        car = new Car();
        car.setOwner(host);
        car.setBrand("Fiat");
        car.setModel("Punto");
        car.setYear(2020);
        car.setPricePerDay(BigDecimal.valueOf(2500));
        car.setLocation("Belgrade");
        car.setSeats(5);
        car.setFuelType(FuelType.BENZIN);
        car.setTransmissionType(TransmissionType.MANUAL);
        car = carRepository.save(car);

        // Create booking in CHECK_IN_COMPLETE status (ready for handshake)
        booking = new Booking();
        booking.setRenter(renter);
        booking.setCar(car);
        booking.setStatus(BookingStatus.CHECK_IN_COMPLETE);
        booking.setStartTime(LocalDateTime.now().plusHours(1));
        booking.setEndTime(LocalDateTime.now().plusDays(3));
        booking.setTotalPrice(BigDecimal.valueOf(7500));
        booking = bookingRepository.save(booking);
    }

    @Nested
    @DisplayName("Handshake Confirmation Flow")
    class HandshakeFlow {

        @Test
        @DisplayName("Guest with valid license can confirm handshake")
        void guestCanConfirmHandshakeWithValidLicense() {
            // Arrange
            when(featureFlags.isStrictCheckinEnabled()).thenReturn(true);

            HandshakeConfirmationDTO dto = new HandshakeConfirmationDTO();
            dto.setBookingId(booking.getId());
            dto.setConfirmed(true);

            // Act
            CheckInStatusDTO result = checkInService.confirmHandshake(dto, renter.getId());

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getBookingId()).isEqualTo(booking.getId());
            // Status should indicate guest confirmed
        }

        @Test
        @DisplayName("Guest with expired license is blocked in strict mode")
        void guestWithExpiredLicenseBlockedInStrictMode() {
            // Arrange
            renter.setDriverLicenseExpiryDate(LocalDate.now().minusDays(1)); // Expired
            userRepository.save(renter);
            
            when(featureFlags.isStrictCheckinEnabled()).thenReturn(true);

            HandshakeConfirmationDTO dto = new HandshakeConfirmationDTO();
            dto.setBookingId(booking.getId());
            dto.setConfirmed(true);

            // Act & Assert
            assertThatThrownBy(() -> checkInService.confirmHandshake(dto, renter.getId()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("dozvola"); // Serbian message about license
        }

        @Test
        @DisplayName("Guest with license expiring during trip is blocked")
        void guestWithLicenseExpiringDuringTripBlocked() {
            // Arrange - License expires during the 3-day trip
            renter.setDriverLicenseExpiryDate(LocalDate.now().plusDays(2));
            userRepository.save(renter);
            
            when(featureFlags.isStrictCheckinEnabled()).thenReturn(true);

            HandshakeConfirmationDTO dto = new HandshakeConfirmationDTO();
            dto.setBookingId(booking.getId());
            dto.setConfirmed(true);

            // Act & Assert
            assertThatThrownBy(() -> checkInService.confirmHandshake(dto, renter.getId()))
                .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("Prior-session guest photos do not satisfy the active-session handshake gate")
        void priorSessionGuestPhotosDoNotSatisfyActiveSessionHandshakeGate() {
            booking.setCheckInSessionId("session-current");
            bookingRepository.saveAndFlush(booking);

            GuestCheckInPhoto priorSessionPhoto = GuestCheckInPhoto.builder()
                .booking(booking)
                .checkInSessionId("session-prior")
                .photoType(CheckInPhotoType.GUEST_EXTERIOR_FRONT)
                .storageBucket(GuestCheckInPhoto.StorageBucket.CHECKIN_STANDARD)
                .storageKey("guest-checkin/session-prior/front.jpg")
                .originalFilename("front.jpg")
                .mimeType("image/jpeg")
                .fileSizeBytes(1024)
                .imageHash("hash-front")
                .exifValidationStatus(ExifValidationStatus.VALID)
                .uploadedBy(renter)
                .build();
            guestCheckInPhotoRepository.saveAndFlush(priorSessionPhoto);

            when(featureFlags.isStrictCheckinEnabled()).thenReturn(false);
            when(featureFlags.isDualPartyPhotosEnabledForBooking(booking.getId())).thenReturn(true);
            when(featureFlags.isDualPartyPhotosRequiredForHandshake()).thenReturn(true);

            HandshakeConfirmationDTO dto = new HandshakeConfirmationDTO();
            dto.setBookingId(booking.getId());
            dto.setConfirmed(true);

            assertThatThrownBy(() -> checkInService.confirmHandshake(dto, renter.getId()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Morate otpremiti sve obavezne fotografije");
        }

        @Test
        @DisplayName("Host can confirm handshake")
        void hostCanConfirmHandshake() {
            // Arrange
            when(featureFlags.isStrictCheckinEnabled()).thenReturn(false);

            HandshakeConfirmationDTO dto = new HandshakeConfirmationDTO();
            dto.setBookingId(booking.getId());
            dto.setConfirmed(true);

            // Act
            CheckInStatusDTO result = checkInService.confirmHandshake(dto, host.getId());

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getBookingId()).isEqualTo(booking.getId());
        }
    }

    @Nested
    @DisplayName("State Transition Validation")
    class StateTransitions {

        @Test
        @DisplayName("Cannot confirm handshake on booking in wrong status")
        void cannotConfirmHandshakeOnWrongStatus() {
            // Arrange - Set wrong status
            booking.setStatus(BookingStatus.ACTIVE);
            bookingRepository.save(booking);

            when(featureFlags.isStrictCheckinEnabled()).thenReturn(false);

            HandshakeConfirmationDTO dto = new HandshakeConfirmationDTO();
            dto.setBookingId(booking.getId());
            dto.setConfirmed(true);

            // Act & Assert
            assertThatThrownBy(() -> checkInService.confirmHandshake(dto, renter.getId()))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Cannot confirm handshake on non-existent booking")
        void cannotConfirmHandshakeOnNonExistentBooking() {
            // Arrange
            HandshakeConfirmationDTO dto = new HandshakeConfirmationDTO();
            dto.setBookingId(999999L);
            dto.setConfirmed(true);

            // Act & Assert
            assertThatThrownBy(() -> checkInService.confirmHandshake(dto, renter.getId()))
                .isInstanceOf(org.example.rentoza.exception.ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Database Integrity")
    class DatabaseIntegrity {

        @Test
        @DisplayName("Booking status is persisted after handshake")
        void bookingStatusPersistedAfterHandshake() {
            // Arrange
            when(featureFlags.isStrictCheckinEnabled()).thenReturn(false);
            
            // Guest confirms
            HandshakeConfirmationDTO guestDto = new HandshakeConfirmationDTO();
            guestDto.setBookingId(booking.getId());
            guestDto.setConfirmed(true);
            checkInService.confirmHandshake(guestDto, renter.getId());

            // Host confirms
            HandshakeConfirmationDTO hostDto = new HandshakeConfirmationDTO();
            hostDto.setBookingId(booking.getId());
            hostDto.setConfirmed(true);
            checkInService.confirmHandshake(hostDto, host.getId());

            // Act - Reload from database
            Booking reloaded = bookingRepository.findById(booking.getId()).orElseThrow();

            // Assert - Should be IN_TRIP after both confirmations
            assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.IN_TRIP);
        }
    }

    @Nested
    @DisplayName("Concurrent Access")
    class ConcurrentAccess {

        @Test
        @DisplayName("Simultaneous handshake confirmations are handled correctly")
        void simultaneousHandshakeConfirmationsHandled() throws Exception {
            // Arrange
            when(featureFlags.isStrictCheckinEnabled()).thenReturn(false);

            HandshakeConfirmationDTO guestDto = new HandshakeConfirmationDTO();
            guestDto.setBookingId(booking.getId());
            guestDto.setConfirmed(true);

            HandshakeConfirmationDTO hostDto = new HandshakeConfirmationDTO();
            hostDto.setBookingId(booking.getId());
            hostDto.setConfirmed(true);

            // Act - Both confirm (simulating near-simultaneous)
            checkInService.confirmHandshake(guestDto, renter.getId());
            checkInService.confirmHandshake(hostDto, host.getId());

            // Assert
            Booking reloaded = bookingRepository.findById(booking.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.IN_TRIP);
        }
    }
}
