package org.example.rentoza.booking;

import org.example.rentoza.booking.cancellation.CancellationPolicyService;
import org.example.rentoza.booking.dto.BookingRequestDTO;
import org.example.rentoza.booking.dto.BookingResponseDTO;
import org.example.rentoza.car.ApprovalStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarBookingSettings;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.chat.ChatServiceClient;
import org.example.rentoza.delivery.DeliveryFeeCalculator;
import org.example.rentoza.exception.BookingConflictException;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.exception.UserOverlapException;
import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.booking.validation.BookingEdgeCaseValidator;
import org.example.rentoza.payment.BookingPaymentService;
import org.example.rentoza.payment.PaymentProvider.PaymentResult;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.RenterVerificationService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.user.dto.BookingEligibilityDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BookingService.
 *
 * <h2>Critical Business Logic Tested</h2>
 * <ul>
 *   <li>Booking creation with valid inputs + payment authorization</li>
 *   <li>Conflict detection (double booking)</li>
 *   <li>User overlap prevention (same renter, same times)</li>
 *   <li>Lead time validation (minimum advance notice)</li>
 *   <li>Trip duration validation (min/max hours/days)</li>
 *   <li>Renter age verification (21+)</li>
 *   <li>Driver license verification</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    @Mock private BookingRepository bookingRepo;
    @Mock private CarRepository carRepo;
    @Mock private UserRepository userRepo;
    @Mock private ReviewRepository reviewRepo;
    @Mock private ChatServiceClient chatServiceClient;
    @Mock private NotificationService notificationService;
    @Mock private CurrentUser currentUser;
    @Mock private CancellationPolicyService cancellationPolicyService;
    @Mock private DeliveryFeeCalculator deliveryFeeCalculator;
    @Mock private RenterVerificationService renterVerificationService;
    @Mock private BookingPaymentService bookingPaymentService;
    @Mock private SchedulerIdempotencyService lockService;
    @Mock private BookingEdgeCaseValidator edgeCaseValidator;

    @InjectMocks
    private BookingService bookingService;

    private User renter;
    private User carOwner;
    private Car car;
    private BookingRequestDTO validBookingRequest;

    /** Convenience: a successful PaymentResult for mocking. */
    private PaymentResult successPayment() {
        PaymentResult r = new PaymentResult();
        ReflectionTestUtils.setField(r, "success", true);
        ReflectionTestUtils.setField(r, "transactionId", "TXN-MOCK-001");
        ReflectionTestUtils.setField(r, "authorizationId", "AUTH-MOCK-001");
        return r;
    }

    @BeforeEach
    void setUp() {
        // Configure @Value fields that @InjectMocks cannot set
        ReflectionTestUtils.setField(bookingService, "approvalEnabled", false);
        ReflectionTestUtils.setField(bookingService, "licenseRequired", true);
        ReflectionTestUtils.setField(bookingService, "approvalSlaHours", 24);
        ReflectionTestUtils.setField(bookingService, "betaUsers", Collections.emptyList());
        ReflectionTestUtils.setField(bookingService, "minGuestPreparationHours", 12);
        ReflectionTestUtils.setField(bookingService, "defaultDepositAmountRsd", 30000);
        ReflectionTestUtils.setField(bookingService, "serviceFeeRate", 0.15);

        // Create test renter
        renter = new User();
        renter.setId(1L);
        renter.setEmail("renter@test.com");
        renter.setFirstName("Test");
        renter.setLastName("Renter");
        renter.setAge(25);
        renter.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);

        // Create test car owner
        carOwner = new User();
        carOwner.setId(2L);
        carOwner.setEmail("owner@test.com");
        carOwner.setFirstName("Car");
        carOwner.setLastName("Owner");

        // Create test car with settings (approved + available)
        car = new Car();
        car.setId(100L);
        car.setOwner(carOwner);
        car.setPricePerDay(BigDecimal.valueOf(5000));
        car.setApprovalStatus(ApprovalStatus.APPROVED);
        car.setListingStatus(org.example.rentoza.car.ListingStatus.APPROVED);
        car.setAvailable(true);

        CarBookingSettings settings = new CarBookingSettings();
        settings.setAdvanceNoticeHours(1);
        settings.setMinTripHours(24);
        settings.setMaxTripDays(30);
        car.setBookingSettings(settings);

        // Create valid booking request (2 days from now, 3-day trip)
        validBookingRequest = new BookingRequestDTO();
        validBookingRequest.setCarId(100L);
        LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2).withHour(10).withMinute(0);
        validBookingRequest.setStartTime(start);
        validBookingRequest.setEndTime(start.plusDays(3));
        validBookingRequest.setInsuranceType("BASIC");
        validBookingRequest.setPrepaidRefuel(false);
        validBookingRequest.setPaymentMethodId("pm_test_default");
    }

    // ========================================================================
    // BOOKING CREATION - HAPPY PATH
    // ========================================================================

    @Nested
    @DisplayName("Booking Creation - Valid Scenarios")
    class BookingCreationValidTests {

        @Test
        @DisplayName("Should create booking with valid inputs and authorize payment")
        void shouldCreateBookingWithValidInputs() {
            // Given
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
                    .thenReturn(BookingEligibilityDTO.eligible());
            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
                Booking b = inv.getArgument(0);
                b.setId(1L);
                return b;
            });
            when(bookingPaymentService.processBookingPayment(anyLong(), anyString()))
                    .thenReturn(successPayment());

            // When
            BookingService.BookingCreationResult creationResult = bookingService.createBooking(validBookingRequest, "renter@test.com");
            Booking result = creationResult.booking();

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getCar()).isEqualTo(car);
            assertThat(result.getRenter()).isEqualTo(renter);
            assertThat(result.getStatus()).isEqualTo(BookingStatus.ACTIVE);
            assertThat(result.getInsuranceType()).isEqualTo("BASIC");
            assertThat(result.getPaymentStatus()).isEqualTo("AUTHORIZED");

            verify(bookingRepo).existsOverlappingBookingsWithLock(
                    eq(100L),
                    eq(validBookingRequest.getStartTime()),
                    eq(validBookingRequest.getEndTime())
            );
            verify(bookingPaymentService).processBookingPayment(eq(1L), anyString());
            // Deposit auth now happens at check-in, not at booking creation
            verify(bookingPaymentService, never()).authorizeDeposit(anyLong(), anyString());
            // storedPaymentMethodId saved for deferred deposit auth
            verify(bookingRepo, atLeastOnce()).save(argThat(b ->
                    b.getStoredPaymentMethodId() != null));
        }

        @Test
        @DisplayName("Should create booking with PENDING_APPROVAL when approval enabled")
        void shouldCreateBookingWithPendingApprovalWhenApprovalEnabled() {
            // Given: Approval feature enabled
            ReflectionTestUtils.setField(bookingService, "approvalEnabled", true);

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
                    .thenReturn(BookingEligibilityDTO.eligible());
            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
                Booking b = inv.getArgument(0);
                b.setId(1L);
                return b;
            });
            when(bookingPaymentService.processBookingPayment(anyLong(), anyString()))
                    .thenReturn(successPayment());

            // When
            BookingService.BookingCreationResult creationResult = bookingService.createBooking(validBookingRequest, "renter@test.com");
            Booking result = creationResult.booking();

            // Then
            assertThat(result.getStatus()).isEqualTo(BookingStatus.PENDING_APPROVAL);
            assertThat(result.getDecisionDeadlineAt()).isNotNull();
        }
    }

    // ========================================================================
    // CONFLICT DETECTION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Booking Conflict Detection")
    class ConflictDetectionTests {

        @Test
        @DisplayName("Should reject booking when car already booked for same dates")
        void shouldRejectBookingWhenCarAlreadyBookedForSameDates() {
            // Given: Car has existing booking for requested times
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(true);

            // When/Then
            assertThatThrownBy(() ->
                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
                    .isInstanceOf(BookingConflictException.class)
                    .hasMessageContaining("already booked");
        }

        @Test
        @DisplayName("Should reject booking when renter has overlapping booking")
        void shouldRejectBookingWhenRenterHasOverlappingBooking() {
            // Given: Renter already has booking for requested times
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(eq(1L), any(), any())).thenReturn(true);

            // When/Then
            assertThatThrownBy(() ->
                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
                    .isInstanceOf(UserOverlapException.class)
                    .hasMessageContaining("Ne možete rezervisati dva vozila u isto vreme");
        }
    }

    // ========================================================================
    // VALIDATION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Lead Time Validation")
    class LeadTimeValidationTests {

        @Test
        @DisplayName("Should reject booking starting within advance notice period")
        void shouldRejectBookingStartingWithinAdvanceNoticePeriod() {
            // Given: Trip starts in 30 minutes (less than 1 hour advance notice)
            validBookingRequest.setStartTime(LocalDateTime.now(SERBIA_ZONE).plusMinutes(30));
            validBookingRequest.setEndTime(LocalDateTime.now(SERBIA_ZONE).plusDays(1).plusMinutes(30));

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);

            // When/Then
            assertThatThrownBy(() ->
                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("najmanje");
        }

        @Test
        @DisplayName("Should accept booking starting after advance notice period")
        void shouldAcceptBookingStartingAfterAdvanceNoticePeriod() {
            // Given: Trip starts in 2 hours (more than 1 hour default)
            LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusHours(2);
            validBookingRequest.setStartTime(start);
            validBookingRequest.setEndTime(start.plusDays(2));

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
                    .thenReturn(BookingEligibilityDTO.eligible());
            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
                Booking b = inv.getArgument(0);
                b.setId(1L);
                return b;
            });
            when(bookingPaymentService.processBookingPayment(anyLong(), anyString()))
                    .thenReturn(successPayment());


            // When
            BookingService.BookingCreationResult creationResult = bookingService.createBooking(validBookingRequest, "renter@test.com");
            Booking result = creationResult.booking();

            // Then
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Trip Duration Validation")
    class TripDurationValidationTests {

        @Test
        @DisplayName("Should reject booking shorter than minimum trip duration")
        void shouldRejectBookingShorterThanMinimumDuration() {
            // Given: Trip of only 12 hours (minimum is 24)
            LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2);
            validBookingRequest.setStartTime(start);
            validBookingRequest.setEndTime(start.plusHours(12));

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);

            // When/Then
            assertThatThrownBy(() ->
                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Minimalno trajanje");
        }

        @Test
        @DisplayName("Should reject booking longer than maximum trip duration")
        void shouldRejectBookingLongerThanMaximumDuration() {
            // Given: Trip of 45 days (maximum is 30)
            LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2);
            validBookingRequest.setStartTime(start);
            validBookingRequest.setEndTime(start.plusDays(45));

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);

            // When/Then
            assertThatThrownBy(() ->
                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Maksimalno trajanje");
        }

        @Test
        @DisplayName("Should accept booking at exactly minimum duration")
        void shouldAcceptBookingAtExactlyMinimumDuration() {
            // Given: Trip of exactly 24 hours
            LocalDateTime start = LocalDateTime.now(SERBIA_ZONE).plusDays(2);
            validBookingRequest.setStartTime(start);
            validBookingRequest.setEndTime(start.plusHours(24));

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
                    .thenReturn(BookingEligibilityDTO.eligible());
            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
                Booking b = inv.getArgument(0);
                b.setId(1L);
                return b;
            });
            when(bookingPaymentService.processBookingPayment(anyLong(), anyString()))
                    .thenReturn(successPayment());

            // When
            BookingService.BookingCreationResult creationResult = bookingService.createBooking(validBookingRequest, "renter@test.com");
            Booking result = creationResult.booking();

            // Then
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Age Verification")
    class AgeVerificationTests {

        @Test
        @DisplayName("Should reject booking when renter is under 21")
        void shouldRejectBookingWhenRenterIsUnder21() {
            // Given: Renter is 19 years old
            renter.setAge(19);
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));

            // When/Then
            assertThatThrownBy(() ->
                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("21");
        }

        @ParameterizedTest(name = "Age {0} should be rejected")
        @ValueSource(ints = {16, 17, 18, 19, 20})
        @DisplayName("Should reject all ages under 21")
        void shouldRejectAllAgesUnder21(int age) {
            // Given
            renter.setAge(age);
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));

            // When/Then
            assertThatThrownBy(() ->
                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("21");
        }

        @Test
        @DisplayName("Should accept booking when renter is exactly 21")
        void shouldAcceptBookingWhenRenterIsExactly21() {
            // Given
            renter.setAge(21);
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
                    .thenReturn(BookingEligibilityDTO.eligible());
            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
                Booking b = inv.getArgument(0);
                b.setId(1L);
                return b;
            });
            when(bookingPaymentService.processBookingPayment(anyLong(), anyString()))
                    .thenReturn(successPayment());

            // When
            BookingService.BookingCreationResult creationResult = bookingService.createBooking(validBookingRequest, "renter@test.com");
            Booking result = creationResult.booking();

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should reject booking when age is null")
        void shouldRejectBookingWhenAgeIsNull() {
            // Given
            renter.setAge(null);
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));

            // When/Then
            assertThatThrownBy(() ->
                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("21");
        }
    }

    @Nested
    @DisplayName("License Verification")
    class LicenseVerificationTests {

        @Test
        @DisplayName("Should reject booking when license not verified")
        void shouldRejectBookingWhenLicenseNotVerified() {
            // Given
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
                    .thenReturn(BookingEligibilityDTO.needsVerification());

            // When/Then
            assertThatThrownBy(() ->
                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("verifikovana");
        }

        @Test
        @DisplayName("Should reject booking when license is expired")
        void shouldRejectBookingWhenLicenseExpired() {
            // Given
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
                    .thenReturn(BookingEligibilityDTO.licenseExpired(java.time.LocalDate.now().minusDays(1)));

            // When/Then
            assertThatThrownBy(() ->
                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("istekla");
        }

        @Test
        @DisplayName("Should allow booking when license check is disabled")
        void shouldAllowBookingWhenLicenseCheckDisabled() {
            // Given: License check disabled
            ReflectionTestUtils.setField(bookingService, "licenseRequired", false);
            renter.setDriverLicenseStatus(DriverLicenseStatus.NOT_STARTED);

            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
                Booking b = inv.getArgument(0);
                b.setId(1L);
                return b;
            });
            when(bookingPaymentService.processBookingPayment(anyLong(), anyString()))
                    .thenReturn(successPayment());

            // When
            BookingService.BookingCreationResult creationResult = bookingService.createBooking(validBookingRequest, "renter@test.com");
            Booking result = creationResult.booking();

            // Then
            assertThat(result).isNotNull();
            verify(renterVerificationService, never()).checkBookingEligibilityForUser(any(), any());
        }
    }

    // ========================================================================
    // ERROR HANDLING TESTS
    // ========================================================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should throw ResourceNotFoundException when user not found")
        void shouldThrowWhenUserNotFound() {
            // Given
            when(userRepo.findByEmail("unknown@test.com")).thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() ->
                    bookingService.createBooking(validBookingRequest, "unknown@test.com"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("User not found");
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when car not found")
        void shouldThrowWhenCarNotFound() {
            // Given
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() ->
                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Car not found");
        }
    }

    // ========================================================================
    // PAYMENT AUTHORIZATION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Payment Authorization")
    class PaymentAuthorizationTests {

        @Test
        @DisplayName("Should throw PaymentAuthorizationException when payment fails")
        void shouldThrowWhenPaymentFails() {
            // Given
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
                    .thenReturn(BookingEligibilityDTO.eligible());
            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
                Booking b = inv.getArgument(0);
                b.setId(1L);
                return b;
            });

            // Payment fails
            PaymentResult failedPayment = new PaymentResult();
            ReflectionTestUtils.setField(failedPayment, "success", false);
            ReflectionTestUtils.setField(failedPayment, "errorMessage", "Insufficient funds");
            ReflectionTestUtils.setField(failedPayment, "errorCode", "INSUFFICIENT_FUNDS");
            when(bookingPaymentService.processBookingPayment(anyLong(), anyString()))
                    .thenReturn(failedPayment);

            // When/Then
            assertThatThrownBy(() ->
                    bookingService.createBooking(validBookingRequest, "renter@test.com"))
                    .isInstanceOf(org.example.rentoza.exception.PaymentAuthorizationException.class)
                    .hasMessageContaining("Autorizacija plaćanja nije uspela");
        }

        @Test
        @DisplayName("Deposit auth no longer happens at booking creation — payment method stored for check-in")
        void shouldStorePaymentMethodForDeferredDepositAuth() {
            // Given: deposit auth was moved to check-in (CheckInScheduler).
            // At booking creation, only the booking payment auth happens and
            // storedPaymentMethodId is saved for later deposit auth.
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
                    .thenReturn(BookingEligibilityDTO.eligible());
            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
                Booking b = inv.getArgument(0);
                b.setId(1L);
                return b;
            });

            when(bookingPaymentService.processBookingPayment(anyLong(), anyString()))
                    .thenReturn(successPayment());

            // When
            bookingService.createBooking(validBookingRequest, "renter@test.com");

            // Then: deposit auth is NOT called at booking time
            verify(bookingPaymentService, never()).authorizeDeposit(anyLong(), anyString());

            // storedPaymentMethodId is persisted on the booking for deferred deposit
            verify(bookingRepo, atLeastOnce()).save(argThat(b ->
                    b.getStoredPaymentMethodId() != null));
        }

        @Test
        @DisplayName("R1-FIX: REDIRECT_REQUIRED persists booking and returns redirect info instead of throwing")
        void shouldReturnRedirectResultWhenPaymentRequires3DS() {
            // Given: payment returns REDIRECT_REQUIRED (3DS/SCA flow)
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
                    .thenReturn(BookingEligibilityDTO.eligible());
            when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> {
                Booking b = inv.getArgument(0);
                b.setId(1L);
                return b;
            });

            // REDIRECT_REQUIRED: success=false but has redirectUrl
            PaymentResult redirectPayment = new PaymentResult();
            ReflectionTestUtils.setField(redirectPayment, "success", false);
            ReflectionTestUtils.setField(redirectPayment, "redirectUrl", "https://3ds.example.com/verify");
            when(bookingPaymentService.processBookingPayment(anyLong(), anyString()))
                    .thenReturn(redirectPayment);

            // When: createBooking should NOT throw; should return redirect result
            BookingService.BookingCreationResult result = bookingService.createBooking(
                    validBookingRequest, "renter@test.com");

            // Then: booking is persisted (not rolled back)
            assertThat(result).isNotNull();
            assertThat(result.redirectRequired()).isTrue();
            assertThat(result.redirectUrl()).isEqualTo("https://3ds.example.com/verify");
            assertThat(result.booking()).isNotNull();
            assertThat(result.booking().getId()).isEqualTo(1L);

            // Booking was saved (not rolled back)
            verify(bookingRepo, atLeastOnce()).save(any(Booking.class));
            // No notifications sent during redirect — webhook will confirm payment
            verify(notificationService, never()).createNotification(any());
        }
    }

    // ========================================================================
    // IDEMPOTENCY REPLAY TESTS (P1 Fix)
    // ========================================================================

    @Nested
    @DisplayName("Idempotency Replay (P1 Fix)")
    class IdempotencyReplayTests {

        private Booking existingBooking;

        @BeforeEach
        void setUpExisting() {
            existingBooking = new Booking();
            existingBooking.setId(42L);
            existingBooking.setCar(car);
            existingBooking.setRenter(renter);
            existingBooking.setPaymentStatus("AUTHORIZED");
            existingBooking.setStatus(BookingStatus.ACTIVE);
        }

        @Test
        @DisplayName("Normal replay: returns existing booking without issuing a duplicate charge")
        void shouldReturnExistingBookingOnNormalReplay() {
            // Given
            validBookingRequest.setIdempotencyKey("idem-key-normal-001");
            when(bookingRepo.findByIdempotencyKeyWithRelations("idem-key-normal-001"))
                    .thenReturn(Optional.of(existingBooking));

            // When
            BookingService.BookingCreationResult result =
                    bookingService.createBooking(validBookingRequest, "renter@test.com");

            // Then: existing booking returned, no duplicate payment charge
            assertThat(result).isNotNull();
            assertThat(result.redirectRequired()).isFalse();
            assertThat(result.booking().getId()).isEqualTo(42L);
            verify(bookingPaymentService, never()).processBookingPayment(anyLong(), anyString());
        }

        @Test
        @DisplayName("SCA replay: returns redirectRequired=true with recovered redirect URL on retry")
        void shouldReturnRedirectEnvelopeOnScaBookingReplay() {
            // Given: booking persisted but still awaiting 3DS completion
            existingBooking.setPaymentStatus("REDIRECT_REQUIRED");
            validBookingRequest.setIdempotencyKey("idem-key-sca-001");
            when(bookingRepo.findByIdempotencyKeyWithRelations("idem-key-sca-001"))
                    .thenReturn(Optional.of(existingBooking));
            when(bookingPaymentService.findPendingRedirectUrl(42L))
                    .thenReturn(Optional.of("https://3ds.bank.example/sca-verify"));

            // When
            BookingService.BookingCreationResult result =
                    bookingService.createBooking(validBookingRequest, "renter@test.com");

            // Then: redirect envelope reconstructed — client receives consistent shape on retry
            assertThat(result).isNotNull();
            assertThat(result.redirectRequired()).isTrue();
            assertThat(result.redirectUrl()).isEqualTo("https://3ds.bank.example/sca-verify");
            assertThat(result.booking().getId()).isEqualTo(42L);
            verify(bookingPaymentService, never()).processBookingPayment(anyLong(), anyString());
        }

        @Test
        @DisplayName("Race-collision: DataIntegrityViolation resolves via DB lookup instead of 500")
        void shouldResolveRaceCollisionViaLookupInsteadOf500() {
            // Given: two concurrent requests with the same key both clear the early check
            validBookingRequest.setIdempotencyKey("idem-key-race-001");
            // First call (early guard) misses; second call (post-collision) finds the winner row
            when(bookingRepo.findByIdempotencyKeyWithRelations("idem-key-race-001"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(existingBooking));
            // Request progresses past guards to repo.save(), which then throws
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
            when(bookingRepo.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
            when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
                    .thenReturn(BookingEligibilityDTO.eligible());
            when(bookingRepo.save(any(Booking.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "ERROR: duplicate key value violates unique constraint \"uk_booking_idempotency_key\""));

            // When
            BookingService.BookingCreationResult result =
                    bookingService.createBooking(validBookingRequest, "renter@test.com");

            // Then: canonical booking returned (200), not a 500
            assertThat(result).isNotNull();
            assertThat(result.redirectRequired()).isFalse();
            assertThat(result.booking().getId()).isEqualTo(42L);
            verify(bookingPaymentService, never()).processBookingPayment(anyLong(), anyString());
        }

        @Test
        @DisplayName("Race-collision via conflict-path: concurrent request sees overlap, resolved via idempotency key")
        void shouldResolveConflictPathRaceViaIdempotencyKey() {
            // Given: request-B reached existsOverlappingBookingsWithLock AFTER request-A committed,
            // so it sees an overlap. Same idempotency key means it's the same booking.
            validBookingRequest.setIdempotencyKey("idem-key-conflict-race-001");
            // Early idempotency check misses (both requests cleared it before A committed);
            // second call (at the conflict-path rescue site) finds request-A's row.
            when(bookingRepo.findByIdempotencyKeyWithRelations("idem-key-conflict-race-001"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(existingBooking));
            when(userRepo.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
            when(carRepo.findById(100L)).thenReturn(Optional.of(car));
            // existsOverlappingBookingsWithLock returns true (request-A's booking committed)
            when(bookingRepo.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(true);

            // When
            BookingService.BookingCreationResult result =
                    bookingService.createBooking(validBookingRequest, "renter@test.com");

            // Then: canonical booking returned (200), not 409 BookingConflictException
            assertThat(result).isNotNull();
            assertThat(result.redirectRequired()).isFalse();
            assertThat(result.booking().getId()).isEqualTo(42L);
            verify(bookingPaymentService, never()).processBookingPayment(anyLong(), anyString());
        }
    }
}
