package org.example.rentoza.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.cancellation.CancelledBy;
import org.example.rentoza.booking.cancellation.CancellationReason;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.booking.cancellation.CancellationSettlementService;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
@Slf4j
class RentalAgreementExpiryProcessor {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");

    private final RentalAgreementRepository agreementRepository;
    private final BookingRepository bookingRepository;
    private final CancellationSettlementService cancellationSettlementService;
    private final NotificationService notificationService;

    @Value("${app.checkin.no-show-minutes-after-trip-start:120}")
    private int acceptanceDeadlineMinutesAfterTripStart;

    @Value("${app.compliance.rental-agreement.renter-breach-penalty-rate:0.20}")
    private BigDecimal renterBreachPenaltyRate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processOverdueAgreement(Long agreementId, LocalDateTime now) {
        agreementRepository.findByIdForUpdate(agreementId).ifPresent(agreement ->
                bookingRepository.findByIdWithRelations(agreement.getBookingId()).ifPresent(booking -> {
                    ensureWorkflowDefaults(agreement, booking);
                    if (agreement.getStatus() == RentalAgreementStatus.FULLY_ACCEPTED
                            || agreement.getStatus() == RentalAgreementStatus.EXPIRED) {
                        return;
                    }
                    if (agreement.getAcceptanceDeadlineAt() == null || agreement.getAcceptanceDeadlineAt().isAfter(now)) {
                        return;
                    }

                    RentalAgreementActor breachActor = determineExpiredActor(agreement);
                    agreement.setStatus(RentalAgreementStatus.EXPIRED);
                    agreement.setExpiredDueToActor(breachActor);
                    agreement.setExpiredReason("ACCEPTANCE_DEADLINE_EXPIRED");
                    agreement.setRequiredNextActor(RentalAgreementActor.NONE);

                    CancellationRecord settlementRecord;
                    if (breachActor == RentalAgreementActor.RENTER) {
                        BigDecimal bookingTotal = booking.getTotalPrice() != null ? booking.getTotalPrice() : BigDecimal.ZERO;
                        BigDecimal penalty = bookingTotal.multiply(renterBreachPenaltyRate).setScale(2, RoundingMode.HALF_UP);
                        BigDecimal refund = bookingTotal.subtract(penalty).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
                        settlementRecord = cancellationSettlementService.beginSettlement(
                                booking,
                                CancelledBy.SYSTEM,
                                CancellationReason.GUEST_AGREEMENT_BREACH,
                                "Rental agreement acceptance deadline missed by renter",
                                penalty,
                                refund,
                                penalty,
                                "RENTER_AGREEMENT_BREACH",
                                "AGREEMENT_V1",
                                booking.getSnapshotDailyRate(),
                                now
                        );
                        cancellationSettlementService.attemptSettlement(settlementRecord, "RENTAL_AGREEMENT_RENTER_BREACH");
                        agreement.setSettlementPolicyApplied("RENTER_20_PERCENT_PENALTY");
                        sendExpiryNotifications(booking, breachActor, penalty, refund);
                    } else {
                        String settlementPolicy = breachActor == RentalAgreementActor.BOTH
                                ? "BOTH_PARTIES_FULL_REFUND"
                                : "OWNER_FULL_REFUND";
                        String narrative = breachActor == RentalAgreementActor.BOTH
                                ? "Rental agreement acceptance deadline missed by both parties"
                                : "Rental agreement acceptance deadline missed by owner";
                        String attemptReason = breachActor == RentalAgreementActor.BOTH
                                ? "RENTAL_AGREEMENT_BOTH_PARTIES_EXPIRY"
                                : "RENTAL_AGREEMENT_OWNER_BREACH";
                        CancellationReason cancellationReason = breachActor == RentalAgreementActor.BOTH
                                ? CancellationReason.SYSTEM_MUTUAL_AGREEMENT_BREACH
                                : CancellationReason.HOST_AGREEMENT_BREACH;
                        CancellationSettlementService.SettlementAttemptResult result =
                                cancellationSettlementService.beginAndAttemptFullRefundSettlement(
                                        booking,
                                        CancelledBy.SYSTEM,
                                        cancellationReason,
                                        narrative,
                                        settlementPolicy,
                                        attemptReason
                                );
                        settlementRecord = result.record();
                        agreement.setSettlementPolicyApplied(settlementPolicy);
                        sendExpiryNotifications(booking, breachActor, BigDecimal.ZERO, settlementRecord.getRefundToGuest());
                    }

                    agreement.setSettlementRecordId(settlementRecord.getId());
                    agreementRepository.saveAndFlush(agreement);
                })
        );
    }

    private void ensureWorkflowDefaults(RentalAgreement agreement, Booking booking) {
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
    }

    private void sendExpiryNotifications(Booking booking,
                                         RentalAgreementActor breachActor,
                                         BigDecimal penalty,
                                         BigDecimal refund) {
        try {
            if (breachActor == RentalAgreementActor.RENTER) {
                if (booking.getRenter() != null) {
                    notificationService.createNotification(CreateNotificationRequestDTO.builder()
                            .recipientId(booking.getRenter().getId())
                            .type(NotificationType.BOOKING_CANCELLED)
                            .message("Rezervacija je automatski otkazana jer ugovor nije prihvaćen na vreme. Zadržana naknada iznosi "
                                    + penalty.setScale(0, RoundingMode.HALF_UP).toPlainString() + " RSD.")
                            .relatedEntityId(String.valueOf(booking.getId()))
                            .build());
                }
                if (booking.getCar() != null && booking.getCar().getOwner() != null) {
                    notificationService.createNotification(CreateNotificationRequestDTO.builder()
                            .recipientId(booking.getCar().getOwner().getId())
                            .type(NotificationType.BOOKING_CANCELLED)
                            .message("Rezervacija je automatski otkazana jer gost nije prihvatio ugovor na vreme.")
                            .relatedEntityId(String.valueOf(booking.getId()))
                            .build());
                }
            } else if (breachActor == RentalAgreementActor.BOTH) {
                if (booking.getRenter() != null) {
                    notificationService.createNotification(CreateNotificationRequestDTO.builder()
                            .recipientId(booking.getRenter().getId())
                            .type(NotificationType.REFUND_PROCESSED)
                            .message("Rezervacija je automatski otkazana jer ugovor nije prihvaćen na vreme od obe strane. Povraćaj od "
                                    + refund.setScale(0, RoundingMode.HALF_UP).toPlainString() + " RSD je pokrenut.")
                            .relatedEntityId(String.valueOf(booking.getId()))
                            .build());
                }
                if (booking.getCar() != null && booking.getCar().getOwner() != null) {
                    notificationService.createNotification(CreateNotificationRequestDTO.builder()
                            .recipientId(booking.getCar().getOwner().getId())
                            .type(NotificationType.BOOKING_CANCELLED)
                            .message("Rezervacija je automatski otkazana jer ugovor nije prihvaćen na vreme od obe strane.")
                            .relatedEntityId(String.valueOf(booking.getId()))
                            .build());
                }
            } else {
                if (booking.getRenter() != null) {
                    notificationService.createNotification(CreateNotificationRequestDTO.builder()
                            .recipientId(booking.getRenter().getId())
                            .type(NotificationType.REFUND_PROCESSED)
                            .message("Rezervacija je automatski otkazana jer domaćin nije prihvatio ugovor na vreme. Povraćaj od "
                                    + refund.setScale(0, RoundingMode.HALF_UP).toPlainString() + " RSD je pokrenut.")
                            .relatedEntityId(String.valueOf(booking.getId()))
                            .build());
                }
                if (booking.getCar() != null && booking.getCar().getOwner() != null) {
                    notificationService.createNotification(CreateNotificationRequestDTO.builder()
                            .recipientId(booking.getCar().getOwner().getId())
                            .type(NotificationType.BOOKING_CANCELLED)
                            .message("Rezervacija je automatski otkazana jer ugovor nije prihvaćen na vreme.")
                            .relatedEntityId(String.valueOf(booking.getId()))
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("[Agreement] Failed to send expiry notifications for booking {}: {}", booking.getId(), e.getMessage());
        }
    }

    private LocalDateTime deriveAcceptanceDeadline(Booking booking) {
        LocalDateTime startTime = booking.getStartTime();
        if (startTime == null && booking.getStartDate() != null) {
            startTime = booking.getStartDate().atStartOfDay();
        }
        if (startTime == null) {
            return LocalDateTime.now(SERBIA_ZONE).plusMinutes(acceptanceDeadlineMinutesAfterTripStart);
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

    private RentalAgreementActor determineExpiredActor(RentalAgreement agreement) {
        boolean ownerAccepted = agreement.getOwnerAcceptedAt() != null;
        boolean renterAccepted = agreement.getRenterAcceptedAt() != null;
        if (!ownerAccepted && !renterAccepted) {
            return RentalAgreementActor.BOTH;
        }
        if (!ownerAccepted) {
            return RentalAgreementActor.OWNER;
        }
        if (!renterAccepted) {
            return RentalAgreementActor.RENTER;
        }
        return RentalAgreementActor.NONE;
    }
}