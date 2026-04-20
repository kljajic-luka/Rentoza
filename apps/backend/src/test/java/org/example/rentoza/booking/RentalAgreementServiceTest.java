package org.example.rentoza.booking;

import org.example.rentoza.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RentalAgreementServiceTest {

    @Mock private RentalAgreementRepository agreementRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private NotificationService notificationService;

    private RentalAgreementService service;
    private RentalAgreement agreement;

    @BeforeEach
    void setUp() {
        service = new RentalAgreementService(agreementRepository, bookingRepository, notificationService);
        ReflectionTestUtils.setField(service, "acceptanceDeadlineMinutesAfterTripStart", 120);

        agreement = RentalAgreement.builder()
                .bookingId(42L)
                .ownerUserId(10L)
                .renterUserId(20L)
                .status(RentalAgreementStatus.PENDING)
                .acceptanceDeadlineAt(LocalDateTime.now().minusMinutes(1))
                .build();

        Booking booking = new Booking();
        booking.setId(42L);
        booking.setStartTime(LocalDateTime.now().plusHours(1));
        when(bookingRepository.findByIdWithRelations(42L)).thenReturn(Optional.of(booking));
    }

    @Test
    @DisplayName("acceptAsOwner rejects acceptance after the deadline has closed")
    void acceptAsOwner_afterDeadline_throwsConflict() {
        when(agreementRepository.findByBookingIdForUpdate(42L)).thenReturn(Optional.of(agreement));

        assertThatThrownBy(() -> service.acceptAsOwner(42L, 10L, "127.0.0.1", "JUnit"))
                .isInstanceOf(RentalAgreementConflictException.class)
                .hasMessage("Rok za prihvatanje ugovora o iznajmljivanju je istekao za ovu rezervaciju.")
                .extracting("code")
                .isEqualTo("RENTAL_AGREEMENT_ACCEPTANCE_CLOSED");

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("acceptAsRenter stays idempotent for an actor who already accepted before expiry")
    void acceptAsRenter_alreadyAccepted_returnsExistingAgreement() {
        agreement.setStatus(RentalAgreementStatus.EXPIRED);
        agreement.setRenterAcceptedAt(Instant.now().minusSeconds(300));
        when(agreementRepository.findByBookingIdForUpdate(42L)).thenReturn(Optional.of(agreement));

        RentalAgreement result = service.acceptAsRenter(42L, 20L, "127.0.0.1", "JUnit");

        assertThat(result).isSameAs(agreement);
    }

    @Test
    @DisplayName("acceptAsOwner backfills a missing deadline from configured runtime policy")
    void acceptAsOwner_backfillsMissingDeadlineFromRuntimePolicy() {
        agreement.setAcceptanceDeadlineAt(null);
        when(agreementRepository.findByBookingIdForUpdate(42L)).thenReturn(Optional.of(agreement));
        when(agreementRepository.saveAndFlush(agreement)).thenReturn(agreement);

        RentalAgreement result = service.acceptAsOwner(42L, 10L, "127.0.0.1", "JUnit");

        assertThat(result.getAcceptanceDeadlineAt()).isNotNull();
        verify(agreementRepository).saveAndFlush(agreement);
    }
}