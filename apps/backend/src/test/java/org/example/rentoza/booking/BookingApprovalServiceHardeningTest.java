package org.example.rentoza.booking;

import org.example.rentoza.booking.cqrs.BookingDomainEvent;
import org.example.rentoza.exception.BookingConflictException;
import org.example.rentoza.notification.Notification;
import org.example.rentoza.notification.NotificationRepository;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.security.InternalServiceJwtUtil;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.car.Car;
import org.example.rentoza.chat.ChatServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingApprovalServiceHardeningTest {

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
    }

    @Test
    void approveBooking_expiresAndRejectsWhenDecisionDeadlineAlreadyPassed() {
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

        Booking booking = new Booking();
        booking.setId(100L);
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setStatus(BookingStatus.PENDING_APPROVAL);
        booking.setDecisionDeadlineAt(LocalDateTime.now(SERBIA_ZONE).minusMinutes(1));

        when(bookingRepository.findByIdWithRelationsForUpdate(100L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> approvalService.approveBooking(100L, 10L))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("expired");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED_SYSTEM);
        assertThat(booking.getPaymentStatus()).isEqualTo("RELEASED");
        assertThat(booking.getDeclineReason()).contains("deadline");

        verify(bookingRepository).save(booking);
        verify(bookingRepository, never()).existsConflictingBookings(anyLong(), any(), any());
        verify(eventPublisher).publishEvent(any(BookingDomainEvent.BookingExpired.class));
    }

    @Test
    void sendPendingApprovalReminders_isIdempotentPerThreshold() {
        User owner = new User();
        owner.setId(11L);

        User renter = new User();
        renter.setId(21L);

        Car car = new Car();
        car.setId(31L);
        car.setOwner(owner);
        car.setBrand("Audi");
        car.setModel("A4");

        Booking booking = new Booking();
        booking.setId(101L);
        booking.setStatus(BookingStatus.PENDING_APPROVAL);
        booking.setDecisionDeadlineAt(LocalDateTime.now(SERBIA_ZONE).plusMinutes(50));
        booking.setCar(car);
        booking.setRenter(renter);

        when(bookingRepository.findPendingBookingsAfter(any(LocalDateTime.class))).thenReturn(List.of(booking));
        when(notificationRepository.findByTypeAndRelatedEntityId(
                NotificationType.BOOKING_APPROVAL_REMINDER,
                "booking-101-approval-reminder-1h"
        )).thenReturn(List.of(), List.of(new Notification()));

        int firstRun = approvalService.sendPendingApprovalReminders();
        int secondRun = approvalService.sendPendingApprovalReminders();

        assertThat(firstRun).isEqualTo(1);
        assertThat(secondRun).isEqualTo(0);

        verify(notificationService, times(1)).createNotification(any());
    }
}
