package org.example.rentoza.booking;

import org.example.rentoza.chat.ChatServiceClient;
import org.example.rentoza.notification.NotificationRepository;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.security.InternalServiceJwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.car.Car;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RentalAgreementGenerationResilienceTest {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    @Mock private BookingRepository bookingRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationService notificationService;
    @Mock private ChatServiceClient chatServiceClient;
    @Mock private InternalServiceJwtUtil internalServiceJwtUtil;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private org.example.rentoza.payment.BookingPaymentService bookingPaymentService;
    @Mock private RentalAgreementService rentalAgreementService;

    private BookingApprovalService approvalService;
    private Booking booking;

    @BeforeEach
    void setUp() {
        approvalService = new BookingApprovalService(
                bookingRepository,
                userRepository,
                notificationRepository,
                notificationService,
                chatServiceClient,
                internalServiceJwtUtil,
                eventPublisher,
                bookingPaymentService,
                rentalAgreementService
        );
        ReflectionTestUtils.setField(approvalService, "approvalSlaHours", 48);

        User owner = new User();
        owner.setId(10L);

        User renter = new User();
        renter.setId(20L);

        Car car = new Car();
        car.setId(30L);
        car.setOwner(owner);
        car.setBrand("BMW");
        car.setModel("X5");
        car.setYear(2023);

        booking = new Booking();
        booking.setId(100L);
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setStatus(BookingStatus.PENDING_APPROVAL);
        booking.setDecisionDeadlineAt(LocalDateTime.now(SERBIA_ZONE).plusHours(2));
        booking.setStartTime(LocalDateTime.now(SERBIA_ZONE).plusDays(1));
        booking.setEndTime(LocalDateTime.now(SERBIA_ZONE).plusDays(3));

        when(bookingRepository.findByIdWithRelationsForUpdate(100L)).thenReturn(Optional.of(booking));
        when(bookingRepository.existsConflictingBookings(anyLong(), any(), any())).thenReturn(false);
        when(userRepository.findById(10L)).thenReturn(Optional.of(owner));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(internalServiceJwtUtil.generateServiceToken("chat-service")).thenReturn("token");
        when(chatServiceClient.createConversationAsync(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    @DisplayName("approveBooking_whenAgreementGenerationFails_bookingStillBecomesActive")
    void approveBooking_whenAgreementGenerationFails_bookingStillBecomesActive() {
        when(rentalAgreementService.generateAgreement(booking))
                .thenThrow(new RuntimeException("agreement generation failed"));

        assertThatCode(() -> approvalService.approveBooking(100L, 10L))
                .doesNotThrowAnyException();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.ACTIVE);
    }

    @Test
    @DisplayName("approveBooking_whenAgreementGenerationSucceeds_invokesAgreementCreationOnce")
    void approveBooking_whenAgreementGenerationSucceeds_invokesAgreementCreationOnce() {
        when(rentalAgreementService.generateAgreement(booking))
                .thenReturn(RentalAgreement.builder()
                        .id(1L)
                        .bookingId(booking.getId())
                        .build());

        approvalService.approveBooking(100L, 10L);

        verify(rentalAgreementService, times(1)).generateAgreement(booking);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.ACTIVE);
    }
}