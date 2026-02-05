//package org.example.rentoza.booking;
//
//import org.example.rentoza.booking.cancellation.CancellationPolicyService;
//import org.example.rentoza.booking.dto.BookingRequestDTO;
//import org.example.rentoza.booking.dto.BookingResponseDTO;
//import org.example.rentoza.car.Car;
//import org.example.rentoza.car.CarBookingSettings;
//import org.example.rentoza.car.CarRepository;
//import org.example.rentoza.chat.ChatServiceClient;
//import org.example.rentoza.delivery.DeliveryFeeCalculator;
//import org.example.rentoza.exception.BookingConflictException;
//import org.example.rentoza.exception.UserOverlapException;
//import org.example.rentoza.exception.ValidationException;
//import org.example.rentoza.notification.NotificationService;
//import org.example.rentoza.review.ReviewRepository;
//import org.example.rentoza.security.CurrentUser;
//import org.example.rentoza.user.DriverLicenseStatus;
//import org.example.rentoza.user.RenterVerificationService;
//import org.example.rentoza.user.User;
//import org.example.rentoza.user.UserRepository;
//import org.example.rentoza.user.dto.BookingEligibilityDTO;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Nested;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.junit.jupiter.params.ParameterizedTest;
//import org.junit.jupiter.params.provider.ValueSource;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.test.util.ReflectionTestUtils;
//
//import java.math.BigDecimal;
//import java.time.LocalDate;
//import java.time.LocalDateTime;
//import java.time.ZoneId;
//import java.util.Optional;
//
//import static org.assertj.core.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
///**
// * Unit tests for BookingService.
// *
// * <h2>Critical Business Logic Tested</h2>
// * <ul>
// *   <li>Booking creation with valid inputs</li>
// *   <li>Conflict detection (double booking)</li>
// *   <li>User overlap prevention (same renter, same times)</li>
// *   <li>Lead time validation (minimum advance notice)</li>
// *   <li>Trip duration validation (min/max hours/days)</li>
// *   <li>Renter age verification (21+)</li>
// *   <li>Driver license verification</li>
// * </ul>
// */
//@ExtendWith(MockitoExtension.class)
//class BookingServiceTest {
//
//    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
//
//    @Mock private BookingRepository bookingRepo;
//    @Mock private CarRepository carRepo;
//    @Mock private UserRepository userRepo;
//    @Mock private ReviewRepository reviewRepo;
//    @Mock private ChatServiceClient chatServiceClient;
//    @Mock private NotificationService notificationService;
//    @Mock private CurrentUser currentUser;
//    @Mock private CancellationPolicyService cancellationPolicyService;
//    @Mock private DeliveryFeeCalculator deliveryFeeCalculator;
//    @Mock private RenterVerificationService renterVerificationService;
//
//    @InjectMocks
//    private BookingService bookingService;
//
//    private User renter;
//    private User carOwner;
//    private Car car;
//    private BookingRequestDTO validBookingRequest;
//
//    @BeforeEach
//    void setUp() {
//        // Configure default settings
//        ReflectionTestUtils.setField(bookingService, "approvalEnabled", false);
//        ReflectionTestUtils.setField(bookingService, "licenseRequired", true);
//        ReflectionTestUtils.setField(bookingService, "expiryHours", 48);
//
//        // Create test renter
//        renter = new User();
//        renter.setId(1L);
//        renter.setEmail("renter@test.com");
//        renter.setFirstName("Test");
//        renter.setLastName("Renter");
//        renter.setAge(25);
//        renter.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);
//
//        // Create test car owner
//        carOwner = new User();
//        carOwner.setId(2L);
//        carOwner.setEmail("owner@test.com");
//        carOwner.setFirstName("Car");
//        carOwner.setLastName("Owner");
//
//        // Create test car with settings
//        car = new Car();
//        car.setId(100L);
//        car.setOwner(carOwner);
//        car.setPricePerDay(BigDecimal.valueOf(5000));
//
//        CarBookingSettings settings = new CarBookingSettings();
//        settings.setAdvanceNoticeHours(1);
//        settings.setMinTripHours(24);
//        settings.setMaxTripDays(30);
//        car.setBookingSettings(settings);
//
//        // Create valid booking request (2 days from now, 3-day trip)
//        validBookingRequest = new BookingRequestDTO();
//        validBookingRequest.setCarId(100L);
//        LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2).withHour(10).withMinute(0);
//        validBookingRequest.setStartTime(start);
//        validBookingRequest.setEndTime(start.plusDays(3));
//        validBookingRequest.setInsuranceType("BASIC");
//        validBookingRequest.setPrepaidRefuel(false);
//    }
//
//    // ========================================================================
//    // BOOKING CREATION - HAPPY PATH
//    // ========================================================================
//
//    @Nested
//    @DisplayName("Booking Creation - Valid Scenarios")
//    class BookingCreationValidTests {
//
//        @Test
//        @DisplayName("Should create booking with valid inputs")
//        void shouldCreateBookingWithValidInputs() {
//            // Given
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
//            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
//            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
//            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
//                    .thenReturn(BookingEligibilityDTO.eligible());
//            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
//                Booking b = inv.getArgument(0);
//                b.setId(1L);
//                return b;
//            });
//
//            // When
//            Booking result = bookingService.createBooking(validBookingRequest, "renter@test.com");
//
//            // Then
//            assertThat(result).isNotNull();
//            assertThat(result.getCar()).isEqualTo(car);
//            assertThat(result.getRenter()).isEqualTo(renter);
//            assertThat(result.getStatus()).isEqualTo(BookingStatus.ACTIVE);
//            assertThat(result.getInsuranceType()).isEqualTo("BASIC");
//
//            verify(bookingRepo).existsOverlappingBookingsWithLock(
//                    eq(100L),
//                    eq(validBookingRequest.getStartTime()),
//                    eq(validBookingRequest.getEndTime())
//            );
//        }
//
//        @Test
//        @DisplayName("Should create booking with PENDING_APPROVAL when approval enabled")
//        void shouldCreateBookingWithPendingApprovalWhenApprovalEnabled() {
//            // Given: Approval feature enabled
//            ReflectionTestUtils.setField(bookingService, "approvalEnabled", true);
//
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
//            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
//            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
//            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
//                    .thenReturn(BookingEligibilityDTO.eligible());
//            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
//                Booking b = inv.getArgument(0);
//                b.setId(1L);
//                return b;
//            });
//
//            // When
//            Booking result = bookingService.createBooking(validBookingRequest, "renter@test.com");
//
//            // Then
//            assertThat(result.getStatus()).isEqualTo(BookingStatus.PENDING_APPROVAL);
//            assertThat(result.getDecisionDeadlineAt()).isNotNull();
//        }
//    }
//
//    // ========================================================================
//    // CONFLICT DETECTION TESTS
//    // ========================================================================
//
//    @Nested
//    @DisplayName("Booking Conflict Detection")
//    class ConflictDetectionTests {
//
//        @Test
//        @DisplayName("Should reject booking when car already booked for same dates")
//        void shouldRejectBookingWhenCarAlreadyBookedForSameDates() {
//            // Given: Car has existing booking for requested times
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
//            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(true);
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
//                    .isInstanceOf(BookingConflictException.class)
//                    .hasMessageContaining("already booked");
//        }
//
//        @Test
//        @DisplayName("Should reject booking when renter has overlapping booking")
//        void shouldRejectBookingWhenRenterHasOverlappingBooking() {
//            // Given: Renter already has booking for requested times
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
//            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
//            when(bookingRepo.existsOverlappingUserBooking(eq(1L), any(), any())).thenReturn(true);
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
//                    .isInstanceOf(UserOverlapException.class)
//                    .hasMessageContaining("Ne možete rezervisati dva vozila u isto vreme");
//        }
//    }
//
//    // ========================================================================
//    // VALIDATION TESTS
//    // ========================================================================
//
//    @Nested
//    @DisplayName("Lead Time Validation")
//    class LeadTimeValidationTests {
//
//        @Test
//        @DisplayName("Should reject booking starting within advance notice period")
//        void shouldRejectBookingStartingWithinAdvanceNoticePeriod() {
//            // Given: Trip starts in 30 minutes (less than 1 hour advance notice)
//            validBookingRequest.setStartTime(LocalDateTime.now(SERBIA_ZONE).plusMinutes(30));
//            validBookingRequest.setEndTime(LocalDateTime.now(SERBIA_ZONE).plusDays(1).plusMinutes(30));
//
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
//            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
//            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
//                    .isInstanceOf(ValidationException.class)
//                    .hasMessageContaining("najmanje");
//        }
//
//        @Test
//        @DisplayName("Should accept booking starting after advance notice period")
//        void shouldAcceptBookingStartingAfterAdvanceNoticePeriod() {
//            // Given: Trip starts in 2 hours (more than 1 hour default)
//            LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusHours(2);
//            validBookingRequest.setStartTime(start);
//            validBookingRequest.setEndTime(start.plusDays(2));
//
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
//            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
//            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
//            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
//                    .thenReturn(BookingEligibilityDTO.eligible());
//            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
//                Booking b = inv.getArgument(0);
//                b.setId(1L);
//                return b;
//            });
//
//            // When
//            Booking result = bookingService.createBooking(validBookingRequest, "renter@test.com");
//
//            // Then
//            assertThat(result).isNotNull();
//        }
//    }
//
//    @Nested
//    @DisplayName("Trip Duration Validation")
//    class TripDurationValidationTests {
//
//        @Test
//        @DisplayName("Should reject booking shorter than minimum trip duration")
//        void shouldRejectBookingShorterThanMinimumDuration() {
//            // Given: Trip of only 12 hours (minimum is 24)
//            LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2);
//            validBookingRequest.setStartTime(start);
//            validBookingRequest.setEndTime(start.plusHours(12));
//
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
//            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
//            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
//                    .isInstanceOf(ValidationException.class)
//                    .hasMessageContaining("Minimalno trajanje");
//        }
//
//        @Test
//        @DisplayName("Should reject booking longer than maximum trip duration")
//        void shouldRejectBookingLongerThanMaximumDuration() {
//            // Given: Trip of 45 days (maximum is 30)
//            LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2);
//            validBookingRequest.setStartTime(start);
//            validBookingRequest.setEndTime(start.plusDays(45));
//
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
//            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
//            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
//                    .isInstanceOf(ValidationException.class)
//                    .hasMessageContaining("Maksimalno trajanje");
//        }
//
//        @Test
//        @DisplayName("Should accept booking at exactly minimum duration")
//        void shouldAcceptBookingAtExactlyMinimumDuration() {
//            // Given: Trip of exactly 24 hours
//            LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2);
//            validBookingRequest.setStartTime(start);
//            validBookingRequest.setEndTime(start.plusHours(24));
//
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
//            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
//            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
//            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
//                    .thenReturn(BookingEligibilityDTO.eligible());
//            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
//                Booking b = inv.getArgument(0);
//                b.setId(1L);
//                return b;
//            });
//
//            // When
//            Booking result = bookingService.createBooking(validBookingRequest, "renter@test.com");
//
//            // Then
//            assertThat(result).isNotNull();
//        }
//    }
//
//    @Nested
//    @DisplayName("Age Verification")
//    class AgeVerificationTests {
//
//        @Test
//        @DisplayName("Should reject booking when renter is under 21")
//        void shouldRejectBookingWhenRenterIsUnder21() {
//            // Given: Renter is 19 years old
//            renter.setAge(19);
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
//                    .isInstanceOf(RuntimeException.class)
//                    .hasMessageContaining("21 years old");
//        }
//
//        @ParameterizedTest(name = "Age {0} should be rejected")
//        @ValueSource(ints = {16, 17, 18, 19, 20})
//        @DisplayName("Should reject all ages under 21")
//        void shouldRejectAllAgesUnder21(int age) {
//            // Given
//            renter.setAge(age);
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
//                    .isInstanceOf(RuntimeException.class)
//                    .hasMessageContaining("21 years old");
//        }
//
//        @Test
//        @DisplayName("Should accept booking when renter is exactly 21")
//        void shouldAcceptBookingWhenRenterIsExactly21() {
//            // Given
//            renter.setAge(21);
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
//            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
//            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
//            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
//                    .thenReturn(BookingEligibilityDTO.eligible());
//            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
//                Booking b = inv.getArgument(0);
//                b.setId(1L);
//                return b;
//            });
//
//            // When
//            Booking result = bookingService.createBooking(validBookingRequest, "renter@test.com");
//
//            // Then
//            assertThat(result).isNotNull();
//        }
//
//        @Test
//        @DisplayName("Should reject booking when age is null")
//        void shouldRejectBookingWhenAgeIsNull() {
//            // Given
//            renter.setAge(null);
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
//                    .isInstanceOf(RuntimeException.class)
//                    .hasMessageContaining("21 years old");
//        }
//    }
//
//    @Nested
//    @DisplayName("License Verification")
//    class LicenseVerificationTests {
//
//        @Test
//        @DisplayName("Should reject booking when license not verified")
//        void shouldRejectBookingWhenLicenseNotVerified() {
//            // Given
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
//            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
//            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
//            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
//                    .thenReturn(BookingEligibilityDTO.needsVerification());
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
//                    .isInstanceOf(ValidationException.class)
//                    .hasMessageContaining("verifikovana");
//        }
//
//        @Test
//        @DisplayName("Should reject booking when license is expired")
//        void shouldRejectBookingWhenLicenseExpired() {
//            // Given
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
//            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
//            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
//            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
//                    .thenReturn(BookingEligibilityDTO.licenseExpired(java.time.LocalDate.now().minusDays(1)));
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
//                    .isInstanceOf(ValidationException.class)
//                    .hasMessageContaining("istekla");
//        }
//
//        @Test
//        @DisplayName("Should allow booking when license check is disabled")
//        void shouldAllowBookingWhenLicenseCheckDisabled() {
//            // Given: License check disabled
//            ReflectionTestUtils.setField(bookingService, "licenseRequired", false);
//            renter.setDriverLicenseStatus(DriverLicenseStatus.NOT_SUBMITTED);
//
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
//            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
//            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
//            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
//                Booking b = inv.getArgument(0);
//                b.setId(1L);
//                return b;
//            });
//
//            // When
//            Booking result = bookingService.createBooking(validBookingRequest, "renter@test.com");
//
//            // Then
//            assertThat(result).isNotNull();
//            verify(renterVerificationService, never()).checkBookingEligibilityForUser(any(), any());
//        }
//    }
//
//    // ========================================================================
//    // ERROR HANDLING TESTS
//    // ========================================================================
//
//    @Nested
//    @DisplayName("Error Handling")
//    class ErrorHandlingTests {
//
//        @Test
//        @DisplayName("Should throw when user not found")
//        void shouldThrowWhenUserNotFound() {
//            // Given
//            when(userRepo.findByEmail("unknown@test.com")).thenReturn(Optional.empty());
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    bookingService.createBooking(validBookingRequest, "unknown@test.com"))
//                    .isInstanceOf(RuntimeException.class)
//                    .hasMessageContaining("User not found");
//        }
//
//        @Test
//        @DisplayName("Should throw when car not found")
//        void shouldThrowWhenCarNotFound() {
//            // Given
//            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
//            when(carRepo.findById(100L)).thenReturn(Optional.empty());
//
//            // When/Then
//            assertThatThrownBy(() ->
//                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
//                    .isInstanceOf(RuntimeException.class)
//                    .hasMessageContaining("Car not found");
//        }
//    }
//}
