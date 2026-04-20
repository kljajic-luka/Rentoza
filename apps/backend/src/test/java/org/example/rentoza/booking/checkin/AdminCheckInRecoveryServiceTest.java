package org.example.rentoza.booking.checkin;

import org.example.rentoza.admin.service.AdminAuditService;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminCheckInRecoveryServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private UserRepository userRepository;
    @Mock private CheckInEventService eventService;
    @Mock private AdminAuditService adminAuditService;

    private AdminCheckInRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new AdminCheckInRecoveryService(
                bookingRepository,
                userRepository,
                eventService,
                adminAuditService
        );
    }

    @Test
    void forceConditionAckShouldTransitionToCheckInCompleteAndEmitEvent() {
        Booking booking = booking(BookingStatus.CHECK_IN_HOST_COMPLETE);
        when(bookingRepository.findByIdWithLock(1L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById(9L)).thenReturn(Optional.of(admin(9L)));

        Booking result = service.forceConditionAck(1L, 9L, "host app crashed");

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CHECK_IN_COMPLETE);
        assertThat(result.getGuestCheckInCompletedAt()).isNotNull();
        assertThat(result.getCheckInAdminOverrideAt()).isNotNull();
        verify(eventService).recordEvent(eq(booking), eq("session-1"), eq(CheckInEventType.ADMIN_FORCE_CONDITION_ACK), eq(9L), eq(CheckInActorRole.SYSTEM), anyMap());
        verify(adminAuditService).logAction(any(), any(), any(), eq(1L), any(), any(), contains("check-in-recovery:FORCE_CONDITION_ACK"));
    }

    @Test
    void cancelStuckShouldRestoreActiveAndClearSession() {
        Booking booking = booking(BookingStatus.CHECK_IN_COMPLETE);
        when(bookingRepository.findByIdWithLock(1L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById(9L)).thenReturn(Optional.of(admin(9L)));

        Booking result = service.cancelStuck(1L, 9L, "stale session");

        assertThat(result.getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(result.getCheckInSessionId()).isNull();
        verify(eventService).recordEvent(eq(booking), eq("session-1"), eq(CheckInEventType.ADMIN_CANCEL_STUCK_CHECKIN), eq(9L), eq(CheckInActorRole.SYSTEM), anyMap());
    }

    @Test
    void reassignShouldRequireCheckInStates() {
        Booking booking = booking(BookingStatus.IN_TRIP);
        when(bookingRepository.findByIdWithLock(1L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.reassignSession(1L, 9L, "reset"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reassignShouldResetSessionBoundStateAndReopenCheckIn() {
        Booking booking = booking(BookingStatus.CHECK_IN_COMPLETE);
        booking.setHostCheckInCompletedAt(Instant.now());
        booking.setGuestCheckInCompletedAt(Instant.now());
        booking.setGuestCheckinPhotoCount(8);
        booking.setGuestCheckinPhotosCompletedAt(Instant.now());
        booking.setHandshakeCompletedAt(Instant.now());
        booking.setTripStartedAt(Instant.now());
        booking.setCheckInAdminOverrideAt(Instant.now());

        when(bookingRepository.findByIdWithLock(1L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById(9L)).thenReturn(Optional.of(admin(9L)));

        Booking result = service.reassignSession(1L, 9L, "reopen evidence");

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CHECK_IN_OPEN);
        assertThat(result.getCheckInSessionId()).isNotNull().isNotEqualTo("session-1");
        assertThat(result.getHostCheckInCompletedAt()).isNull();
        assertThat(result.getGuestCheckInCompletedAt()).isNull();
        assertThat(result.getGuestCheckinPhotoCount()).isNull();
        assertThat(result.getGuestCheckinPhotosCompletedAt()).isNull();
        assertThat(result.getHandshakeCompletedAt()).isNull();
        assertThat(result.getTripStartedAt()).isNull();
        assertThat(result.getCheckInAdminOverrideAt()).isNull();
        verify(eventService).recordEvent(eq(booking), eq(result.getCheckInSessionId()), eq(CheckInEventType.ADMIN_REASSIGN_CHECKIN_SESSION), eq(9L), eq(CheckInActorRole.SYSTEM), anyMap());
    }

    private Booking booking(BookingStatus status) {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setStatus(status);
        booking.setCheckInSessionId("session-1");
        booking.setCar(new Car());
        User owner = new User();
        owner.setId(100L);
        booking.getCar().setOwner(owner);
        User renter = new User();
        renter.setId(200L);
        booking.setRenter(renter);
        return booking;
    }

    private User admin(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
