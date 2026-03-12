package org.example.rentoza.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RentalAgreementBackfillServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private RentalAgreementRepository agreementRepository;
    @Mock private RentalAgreementService agreementService;

    private RentalAgreementBackfillService backfillService;

    @BeforeEach
    void setUp() {
        backfillService = new RentalAgreementBackfillService(
                bookingRepository,
                agreementRepository,
                agreementService
        );
    }

    @Test
    @DisplayName("backfill queries approved-and-later statuses but excludes pending approval")
    void backfillEligibilityExcludesPendingApproval() {
        when(bookingRepository.findByStatusInWithRelations(anyList())).thenReturn(List.of());

        backfillService.backfillAgreements();

        ArgumentCaptor<List<BookingStatus>> statusesCaptor = ArgumentCaptor.forClass(List.class);
        verify(bookingRepository).findByStatusInWithRelations(statusesCaptor.capture());

        assertThat(statusesCaptor.getValue())
                .contains(BookingStatus.ACTIVE, BookingStatus.APPROVED, BookingStatus.CHECKOUT_DAMAGE_DISPUTE)
                .doesNotContain(BookingStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("backfill generates missing agreement for checkout damage dispute booking")
    void backfillGeneratesForCheckoutDamageDispute() {
        Booking booking = new Booking();
        booking.setId(67L);
        booking.setStatus(BookingStatus.CHECKOUT_DAMAGE_DISPUTE);

        when(bookingRepository.findByStatusInWithRelations(anyList())).thenReturn(List.of(booking));
        when(agreementRepository.findBookingIdsWithAgreements(List.of(67L))).thenReturn(List.of());
        when(agreementService.generateAgreement(booking))
                .thenReturn(RentalAgreement.builder().bookingId(67L).build());

        RentalAgreementBackfillService.BackfillResult result = backfillService.backfillAgreements();

        assertThat(result).isEqualTo(new RentalAgreementBackfillService.BackfillResult(1, 0, 0));
        verify(agreementService).generateAgreement(booking);
    }
}
