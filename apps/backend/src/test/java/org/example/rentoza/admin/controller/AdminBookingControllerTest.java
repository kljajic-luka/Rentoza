package org.example.rentoza.admin.controller;

import org.example.rentoza.admin.dto.ForceCompleteBookingRequest;
import org.example.rentoza.admin.service.AdminAuditService;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.booking.BookingRepository;
import org.example.rentoza.booking.BookingStatus;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminBookingController Tests")
class AdminBookingControllerTest {

    @Mock
    private BookingRepository bookingRepo;

    @Mock
    private UserRepository userRepo;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private AdminAuditService auditService;

    @Mock
    private org.example.rentoza.booking.RentalAgreementBackfillService agreementBackfillService;

    private AdminBookingController controller;
    private User admin;

    @BeforeEach
    void setUp() {
        controller = new AdminBookingController(
            bookingRepo,
            userRepo,
            currentUser,
            auditService,
            agreementBackfillService
        );

        admin = new User();
        admin.setId(99L);

        when(currentUser.id()).thenReturn(99L);
        when(userRepo.findById(99L)).thenReturn(Optional.of(admin));
    }

    @Test
    @DisplayName("Force-complete rejects checkout settlement pending bookings")
    void forceCompleteRejectsCheckoutSettlementPending() {
        Booking booking = new Booking();
        booking.setId(123L);
        booking.setStatus(BookingStatus.CHECKOUT_SETTLEMENT_PENDING);
        when(bookingRepo.findByIdWithLock(123L)).thenReturn(Optional.of(booking));

        ResponseEntity<?> response = controller.forceComplete(
            123L,
            new ForceCompleteBookingRequest("Manual intervention request")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of(
            "error", "SETTLEMENT_PENDING",
            "message", "Cannot force-complete while checkout settlement is still pending."
        ));
        verify(bookingRepo, never()).save(booking);
        verify(auditService, never()).logAction(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("Force-complete rejects cancellation settlement pending bookings")
    void forceCompleteRejectsCancellationSettlementPending() {
        Booking booking = new Booking();
        booking.setId(456L);
        booking.setStatus(BookingStatus.CANCELLATION_PENDING_SETTLEMENT);
        when(bookingRepo.findByIdWithLock(456L)).thenReturn(Optional.of(booking));

        ResponseEntity<?> response = controller.forceComplete(
            456L,
            new ForceCompleteBookingRequest("Manual intervention request")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of(
            "error", "SETTLEMENT_PENDING",
            "message", "Cannot force-complete while cancellation settlement is still pending."
        ));
        verify(bookingRepo, never()).save(booking);
    }
}