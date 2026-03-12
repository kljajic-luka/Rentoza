package org.example.rentoza.booking;

import org.example.rentoza.car.Car;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Tests for lazy agreement generation in the GET endpoint flow.
 * Covers the scenario where agreement generation failed silently during
 * booking approval and the GET endpoint must recover by generating on-demand.
 */
@ExtendWith(MockitoExtension.class)
class RentalAgreementLazyGenerationTest {

    @Mock private RentalAgreementRepository agreementRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private NotificationService notificationService;

    private RentalAgreementService service;
    private Booking booking;

    @BeforeEach
    void setUp() {
        service = new RentalAgreementService(agreementRepository, bookingRepository, notificationService);

        User owner = new User();
        owner.setId(10L);
        owner.setFirstName("Host");
        owner.setLastName("User");

        User renter = new User();
        renter.setId(20L);
        renter.setFirstName("Guest");
        renter.setLastName("User");

        Car car = new Car();
        car.setId(30L);
        car.setOwner(owner);
        car.setBrand("BMW");
        car.setModel("X5");
        car.setYear(2023);
        car.setLicensePlate("BG-123-AB");

        booking = new Booking();
        booking.setId(67L);
        booking.setCar(car);
        booking.setRenter(renter);
        booking.setTotalPrice(new BigDecimal("15000"));
        booking.setInsuranceType("BASIC");
        booking.setPrepaidRefuel(false);
        booking.setStartTime(LocalDateTime.of(2026, 3, 15, 10, 0));
        booking.setEndTime(LocalDateTime.of(2026, 3, 18, 10, 0));
    }

    @Test
    @DisplayName("getOrGenerateAgreement returns existing agreement without generating")
    void returnsExistingAgreement() {
        RentalAgreement existing = RentalAgreement.builder()
                .id(1L).bookingId(67L).build();
        when(agreementRepository.findByBookingId(67L)).thenReturn(Optional.of(existing));

        Optional<RentalAgreement> result = service.getOrGenerateAgreement(67L);

        assertThat(result).isPresent().contains(existing);
        verify(bookingRepository, never()).findByIdWithRelations(anyLong());
    }

    @Test
    @DisplayName("getOrGenerateAgreement generates agreement for ACTIVE booking without one")
    void lazyGeneratesForActiveBooking() {
        booking.setStatus(BookingStatus.ACTIVE);
        when(bookingRepository.findByIdWithRelations(67L)).thenReturn(Optional.of(booking));

        RentalAgreement generated = RentalAgreement.builder()
                .id(2L).bookingId(67L).build();
        // generateAgreement checks repo again internally (idempotency)
        when(agreementRepository.findByBookingId(67L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(agreementRepository.save(any(RentalAgreement.class))).thenReturn(generated);

        Optional<RentalAgreement> result = service.getOrGenerateAgreement(67L);

        assertThat(result).isPresent();
        verify(agreementRepository).save(any(RentalAgreement.class));
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {
            "APPROVED", "CHECK_IN_OPEN", "CHECK_IN_HOST_COMPLETE", "CHECK_IN_COMPLETE",
            "IN_TRIP", "CHECKOUT_OPEN", "CHECKOUT_GUEST_COMPLETE",
            "CHECKOUT_HOST_COMPLETE", "CHECKOUT_SETTLEMENT_PENDING", "CHECKOUT_DAMAGE_DISPUTE"
    })
    @DisplayName("getOrGenerateAgreement generates for post-approval statuses")
    void lazyGeneratesForPostApprovalStatuses(BookingStatus status) {
        booking.setStatus(status);
        when(agreementRepository.findByBookingId(67L)).thenReturn(Optional.empty());
        when(bookingRepository.findByIdWithRelations(67L)).thenReturn(Optional.of(booking));
        when(agreementRepository.save(any(RentalAgreement.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Optional<RentalAgreement> result = service.getOrGenerateAgreement(67L);

        assertThat(result).isPresent();
        verify(notificationService, never()).createNotification(any());
    }

    @Test
    @DisplayName("getOrGenerateAgreement returns empty for PENDING_APPROVAL (not yet approved)")
    void doesNotGenerateForPendingApproval() {
        booking.setStatus(BookingStatus.PENDING_APPROVAL);
        when(agreementRepository.findByBookingId(67L)).thenReturn(Optional.empty());
        when(bookingRepository.findByIdWithRelations(67L)).thenReturn(Optional.of(booking));

        Optional<RentalAgreement> result = service.getOrGenerateAgreement(67L);

        assertThat(result).isEmpty();
        verify(agreementRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {
            "CANCELLED", "DECLINED", "COMPLETED", "EXPIRED", "EXPIRED_SYSTEM",
            "NO_SHOW_HOST", "NO_SHOW_GUEST"
    })
    @DisplayName("getOrGenerateAgreement returns empty for terminal statuses")
    void doesNotGenerateForTerminalStatuses(BookingStatus status) {
        booking.setStatus(status);
        when(agreementRepository.findByBookingId(67L)).thenReturn(Optional.empty());
        when(bookingRepository.findByIdWithRelations(67L)).thenReturn(Optional.of(booking));

        Optional<RentalAgreement> result = service.getOrGenerateAgreement(67L);

        assertThat(result).isEmpty();
        verify(agreementRepository, never()).save(any());
    }

    @Test
    @DisplayName("getOrGenerateAgreement returns empty for nonexistent booking")
    void returnsEmptyForNonexistentBooking() {
        when(agreementRepository.findByBookingId(999L)).thenReturn(Optional.empty());
        when(bookingRepository.findByIdWithRelations(999L)).thenReturn(Optional.empty());

        Optional<RentalAgreement> result = service.getOrGenerateAgreement(999L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getOrGenerateAgreement does not notify parties during lazy recovery")
    void lazyGenerationDoesNotNotifyParties() {
        booking.setStatus(BookingStatus.ACTIVE);
        when(agreementRepository.findByBookingId(67L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(bookingRepository.findByIdWithRelations(67L)).thenReturn(Optional.of(booking));
        when(agreementRepository.save(any(RentalAgreement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<RentalAgreement> result = service.getOrGenerateAgreement(67L);

        assertThat(result).isPresent();
        verify(notificationService, never()).createNotification(any());
    }

    @Test
    @DisplayName("getOrGenerateAgreement recovers from duplicate insert race")
    void recoversFromDuplicateInsertRace() {
        booking.setStatus(BookingStatus.ACTIVE);
        RentalAgreement existing = RentalAgreement.builder()
                .id(9L)
                .bookingId(67L)
                .build();

        when(agreementRepository.findByBookingId(67L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(bookingRepository.findByIdWithRelations(67L)).thenReturn(Optional.of(booking));
        when(agreementRepository.save(any(RentalAgreement.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        Optional<RentalAgreement> result = service.getOrGenerateAgreement(67L);

        assertThat(result).contains(existing);
        verify(notificationService, never()).createNotification(any());
    }
}
