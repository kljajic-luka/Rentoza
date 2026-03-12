package org.example.rentoza.booking;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class RentalAgreementBackfillProcessor {

    private final RentalAgreementRepository agreementRepository;
    private final BookingRepository bookingRepository;

    @Value("${app.checkin.no-show-minutes-after-trip-start:120}")
    private int acceptanceDeadlineMinutesAfterTripStart;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void backfillWorkflowDefaults(Long agreementId) {
        agreementRepository.findByIdForUpdate(agreementId).ifPresent(agreement ->
                bookingRepository.findByIdWithRelations(agreement.getBookingId()).ifPresent(booking -> {
                    boolean changed = false;
                    if (agreement.getAcceptanceDeadlineAt() == null) {
                        agreement.setAcceptanceDeadlineAt(deriveAcceptanceDeadline(booking));
                        changed = true;
                    }
                    if (agreement.getRequiredNextActor() == null) {
                        agreement.setRequiredNextActor(determineRequiredNextActor(agreement));
                        changed = true;
                    }
                    if (changed) {
                        agreementRepository.saveAndFlush(agreement);
                    }
                })
        );
    }

    private java.time.LocalDateTime deriveAcceptanceDeadline(Booking booking) {
        java.time.LocalDateTime startTime = booking.getStartTime();
        if (startTime == null && booking.getStartDate() != null) {
            startTime = booking.getStartDate().atStartOfDay();
        }
        if (startTime == null) {
            return java.time.LocalDateTime.now(java.time.ZoneId.of("Europe/Belgrade"))
                    .plusMinutes(acceptanceDeadlineMinutesAfterTripStart);
        }
        return startTime.plusMinutes(acceptanceDeadlineMinutesAfterTripStart);
    }

    private RentalAgreementActor determineRequiredNextActor(RentalAgreement agreement) {
        boolean ownerAccepted = agreement.getOwnerAcceptedAt() != null;
        boolean renterAccepted = agreement.getRenterAcceptedAt() != null;
        if (ownerAccepted && renterAccepted) {
            return RentalAgreementActor.NONE;
        }
        if (!ownerAccepted && !renterAccepted) {
            return RentalAgreementActor.BOTH;
        }
        return ownerAccepted ? RentalAgreementActor.RENTER : RentalAgreementActor.OWNER;
    }
}