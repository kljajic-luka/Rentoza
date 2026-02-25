package org.example.rentoza.booking;

import org.example.rentoza.booking.cancellation.CancellationPolicyService;
import org.example.rentoza.booking.dto.BookingRequestDTO;
import org.example.rentoza.car.Car;
import org.example.rentoza.car.CarBookingSettings;
import org.example.rentoza.car.CarRepository;
import org.example.rentoza.chat.ChatServiceClient;
import org.example.rentoza.delivery.DeliveryFeeCalculator;
import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.review.ReviewRepository;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.DriverLicenseStatus;
import org.example.rentoza.user.RenterVerificationService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.user.dto.BookingEligibilityDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceApprovalDeadlinePolicyTest {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    @Mock private BookingRepository bookingRepository;
    @Mock private CarRepository carRepository;
    @Mock private UserRepository userRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private ChatServiceClient chatServiceClient;
    @Mock private NotificationService notificationService;
    @Mock private CurrentUser currentUser;
    @Mock private CancellationPolicyService cancellationPolicyService;
    @Mock private DeliveryFeeCalculator deliveryFeeCalculator;
    @Mock private RenterVerificationService renterVerificationService;
    @Mock private org.example.rentoza.payment.BookingPaymentService bookingPaymentService;
    @Mock private org.example.rentoza.scheduler.SchedulerIdempotencyService lockService;

    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(
                bookingRepository,
                carRepository,
                userRepository,
                reviewRepository,
                chatServiceClient,
                notificationService,
                currentUser,
                cancellationPolicyService,
                deliveryFeeCalculator,
                renterVerificationService,
                bookingPaymentService,
                lockService
        );

        ReflectionTestUtils.setField(bookingService, "approvalSlaHours", 48);
        ReflectionTestUtils.setField(bookingService, "minGuestPreparationHours", 12);
    }

    @Test
    void calculateDecisionDeadline_usesSlaWhenSlaEarlierThanPreparationBuffer() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 2, 15, 10, 0);
        LocalDateTime tripStart = createdAt.plusHours(72);

        LocalDateTime deadline = bookingService.calculateDecisionDeadline(createdAt, tripStart);

        assertThat(deadline).isEqualTo(createdAt.plusHours(48));
    }

    @Test
    void calculateDecisionDeadline_usesPreparationBufferWhenTripIsSooner() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 2, 15, 10, 0);
        LocalDateTime tripStart = createdAt.plusHours(18);

        LocalDateTime deadline = bookingService.calculateDecisionDeadline(createdAt, tripStart);

        assertThat(deadline).isEqualTo(tripStart.minusHours(12));
    }

    @Test
    void createBooking_rejectsPendingApprovalWhenComputedDeadlineAlreadyExpired() {
        ReflectionTestUtils.setField(bookingService, "approvalEnabled", true);
        ReflectionTestUtils.setField(bookingService, "licenseRequired", true);

        User renter = new User();
        renter.setId(101L);
        renter.setEmail("renter@test.com");
        renter.setAge(25);
        renter.setDriverLicenseStatus(DriverLicenseStatus.APPROVED);

        User owner = new User();
        owner.setId(202L);

        CarBookingSettings settings = new CarBookingSettings();
        settings.setAdvanceNoticeHours(1);
        settings.setMinTripHours(24);
        settings.setMaxTripDays(30);
        settings.setInstantBookEnabled(false);

        Car car = new Car();
        car.setId(1L);
        car.setOwner(owner);
        car.setPricePerDay(new BigDecimal("5000.00"));
        car.setBookingSettings(settings);
        car.setApprovalStatus(org.example.rentoza.car.ApprovalStatus.APPROVED);
        car.setAvailable(true);

        LocalDateTime now = LocalDateTime.now(SERBIA_ZONE);
        BookingRequestDTO request = new BookingRequestDTO();
        request.setCarId(1L);
        request.setStartTime(now.plusHours(11)); // with 12h preparation buffer => deadline already passed
        request.setEndTime(now.plusHours(35));

        when(userRepository.findByEmail("renter@test.com")).thenReturn(Optional.of(renter));
        when(carRepository.findById(1L)).thenReturn(Optional.of(car));
        when(bookingRepository.existsOverlappingBookingsWithLock(anyLong(), any(), any())).thenReturn(false);
        when(bookingRepository.existsOverlappingUserBooking(anyLong(), any(), any())).thenReturn(false);
        when(renterVerificationService.checkBookingEligibilityForUser(any(), any()))
                .thenReturn(BookingEligibilityDTO.eligible());

        assertThatThrownBy(() -> bookingService.createBooking(request, "renter@test.com"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("rok za odluku je već istekao");

        verify(bookingRepository, never()).save(any(Booking.class));
    }
}
