package org.example.rentoza.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.cancellation.CancelledBy;
import org.example.rentoza.booking.cancellation.CancellationReason;
import org.example.rentoza.booking.cancellation.CancellationRecord;
import org.example.rentoza.booking.cancellation.CancellationSettlementService;
import org.example.rentoza.booking.dto.AgreementSummaryDTO;
import org.example.rentoza.config.FeatureFlags;
import org.example.rentoza.notification.NotificationService;
import org.example.rentoza.notification.NotificationType;
import org.example.rentoza.notification.dto.CreateNotificationRequestDTO;
import org.example.rentoza.scheduler.SchedulerIdempotencyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RentalAgreementWorkflowService {

    private static final ZoneId SERBIA_ZONE = ZoneId.of("Europe/Belgrade");
    private static final String LOCK_EXPIRY = "rental-agreement.expiry";
    private static final String LOCK_REMINDER = "rental-agreement.reminder";
    private static final String STATUS_LEGACY = "LEGACY";
    private static final String STATUS_COMPLETE = "AGREEMENT_COMPLETE";
    private static final String STATUS_PENDING_BOTH = "AGREEMENT_PENDING_BOTH";
    private static final String STATUS_PENDING_OWNER = "AGREEMENT_PENDING_OWNER";
    private static final String STATUS_PENDING_RENTER = "AGREEMENT_PENDING_RENTER";
    private static final String STATUS_EXPIRED_OWNER = "AGREEMENT_EXPIRED_OWNER_BREACH";
    private static final String STATUS_EXPIRED_RENTER = "AGREEMENT_EXPIRED_RENTER_BREACH";
    private static final String STATUS_EXPIRED_BOTH = "AGREEMENT_EXPIRED_BOTH_PARTIES";

    private static final String ACTION_ACCEPT = "ACCEPT_RENTAL_AGREEMENT";
    private static final String ACTION_OPEN_CHECKIN = "OPEN_CHECK_IN";
    private static final String ACTION_WAIT = "WAIT_FOR_OTHER_PARTY";
    private static final String ACTION_VIEW = "VIEW_BOOKING_DETAILS";
    private static final List<RentalAgreementStatus> ACTIVE_AGREEMENT_STATUSES = List.of(
            RentalAgreementStatus.PENDING,
            RentalAgreementStatus.OWNER_ACCEPTED,
            RentalAgreementStatus.RENTER_ACCEPTED
    );

    private final RentalAgreementRepository agreementRepository;
    private final BookingRepository bookingRepository;
    private final CancellationSettlementService cancellationSettlementService;
    private final NotificationService notificationService;
    private final FeatureFlags featureFlags;
    private final SchedulerIdempotencyService lockService;
    private final RentalAgreementService rentalAgreementService;
    private final RentalAgreementBackfillProcessor rentalAgreementBackfillProcessor;
    private final RentalAgreementExpiryProcessor rentalAgreementExpiryProcessor;

    @Value("${app.checkin.no-show-minutes-after-trip-start:120}")
    private int acceptanceDeadlineMinutesAfterTripStart;

    @Value("${app.compliance.rental-agreement.renter-breach-penalty-rate:0.20}")
    private BigDecimal renterBreachPenaltyRate;

    public Map<Long, AgreementSummaryDTO> buildSummaries(Collection<Booking> bookings, Long currentUserId) {
        if (bookings == null || bookings.isEmpty()) {
            return Map.of();
        }

        List<Long> bookingIds = bookings.stream()
                .map(Booking::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, RentalAgreement> agreementsByBookingId = agreementRepository.findByBookingIdIn(bookingIds).stream()
                .collect(Collectors.toMap(RentalAgreement::getBookingId, Function.identity()));

        return bookings.stream().collect(Collectors.toMap(
                Booking::getId,
            booking -> buildSummary(booking, agreementsByBookingId.get(booking.getId()), currentUserId)
        ));
    }

    public AgreementSummaryDTO buildSummary(Booking booking, RentalAgreement agreement, Long currentUserId) {
        if (agreement == null) {
            if (!rentalAgreementService.shouldHaveAgreement(booking)) {
            return new AgreementSummaryDTO(
                STATUS_LEGACY,
                false,
                false,
                false,
                true,
                true,
                null,
                "NONE",
                ACTION_OPEN_CHECKIN
            );
            }

            boolean isOwner = currentUserId != null
                && booking.getCar() != null
                && booking.getCar().getOwner() != null
                && currentUserId.equals(booking.getCar().getOwner().getId());
            boolean isRenter = currentUserId != null
                && booking.getRenter() != null
                && currentUserId.equals(booking.getRenter().getId());
            boolean enforcementEnabled = featureFlags.isRentalAgreementCheckinEnforced();
            boolean currentActorNeedsAcceptance = isOwner || isRenter;
            LocalDateTime deadline = deriveAcceptanceDeadline(booking);
            return new AgreementSummaryDTO(
                STATUS_PENDING_BOTH,
                    false,
                    false,
                currentActorNeedsAcceptance,
                !enforcementEnabled,
                false,
                deadline,
                resolveUrgency(deadline),
                resolveRecommendedAction(
                    STATUS_PENDING_BOTH,
                    currentActorNeedsAcceptance,
                    !enforcementEnabled
                )
            );
        }

        LocalDateTime deadline = agreement.getAcceptanceDeadlineAt() != null
                ? agreement.getAcceptanceDeadlineAt()
                : deriveAcceptanceDeadline(booking);

        boolean ownerAccepted = agreement.getOwnerAcceptedAt() != null;
        boolean renterAccepted = agreement.getRenterAcceptedAt() != null;
        boolean isOwner = currentUserId != null
                && booking.getCar() != null
                && booking.getCar().getOwner() != null
                && currentUserId.equals(booking.getCar().getOwner().getId());
        boolean isRenter = currentUserId != null
                && booking.getRenter() != null
                && currentUserId.equals(booking.getRenter().getId());

        boolean currentActorNeedsAcceptance = isOwner
                ? !ownerAccepted
                : isRenter && !renterAccepted;

        boolean enforcementEnabled = featureFlags.isRentalAgreementCheckinEnforced();
        boolean currentActorCanProceedToCheckIn;
        if (!enforcementEnabled) {
            currentActorCanProceedToCheckIn = true;
        } else if (agreement.getStatus() == RentalAgreementStatus.FULLY_ACCEPTED) {
            currentActorCanProceedToCheckIn = true;
        } else {
            currentActorCanProceedToCheckIn = isOwner && ownerAccepted && !renterAccepted;
        }

        return new AgreementSummaryDTO(
                resolveWorkflowStatus(agreement, ownerAccepted, renterAccepted),
                ownerAccepted,
                renterAccepted,
                currentActorNeedsAcceptance,
                currentActorCanProceedToCheckIn,
                false,
                deadline,
                resolveUrgency(deadline),
                resolveRecommendedAction(
                        resolveWorkflowStatus(agreement, ownerAccepted, renterAccepted),
                        currentActorNeedsAcceptance,
                    currentActorCanProceedToCheckIn
                )
        );
    }

    @Transactional
    @Scheduled(cron = "${app.compliance.rental-agreement.expiry-cron:0 */15 * * * *}", zone = "Europe/Belgrade")
    public void expireOverdueAgreements() {
        if (!featureFlags.isRentalAgreementCheckinEnforced()) {
            return;
        }
        if (!lockService.tryAcquireLock(LOCK_EXPIRY, Duration.ofMinutes(14))) {
            log.debug("[Agreement] Skipping expiry job — lock held by another instance");
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now(SERBIA_ZONE);
            backfillMissingDeadlinesAndActors();
            List<Long> overdueIds = agreementRepository.findPendingIdsForDeadlineResolution(ACTIVE_AGREEMENT_STATUSES, now);

            for (Long agreementId : overdueIds) {
                try {
                    rentalAgreementExpiryProcessor.processOverdueAgreement(agreementId, now);
                } catch (Exception e) {
                    log.error("[Agreement] Failed to process overdue agreement {}: {}", agreementId, e.getMessage(), e);
                }
            }
        } finally {
            lockService.releaseLock(LOCK_EXPIRY);
        }
    }

    @Transactional
    @Scheduled(cron = "${app.compliance.rental-agreement.reminder-cron:0 0 * * * *}", zone = "Europe/Belgrade")
    public void sendPendingAgreementReminders() {
        if (!featureFlags.isRentalAgreementCheckinEnforced()) {
            return;
        }
        if (!lockService.tryAcquireLock(LOCK_REMINDER, Duration.ofMinutes(55))) {
            log.debug("[Agreement] Skipping reminder job — lock held by another instance");
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now(SERBIA_ZONE);
            LocalDateTime twentyFourHours = now.plusHours(24);
            LocalDateTime twoHours = now.plusHours(2);
            backfillMissingDeadlinesAndActors();

            List<RentalAgreement> dueSoon = agreementRepository.findPendingForReminderWindow(
                ACTIVE_AGREEMENT_STATUSES,
                    now,
                    twentyFourHours
            );

            for (RentalAgreement agreement : dueSoon) {
                bookingRepository.findByIdWithRelations(agreement.getBookingId()).ifPresent(booking -> {
                    ensureWorkflowDefaults(agreement, booking);
                    if (agreement.getStatus() == RentalAgreementStatus.FULLY_ACCEPTED || agreement.getStatus() == RentalAgreementStatus.EXPIRED) {
                        return;
                    }

                    String bucket = agreement.getAcceptanceDeadlineAt() != null && !agreement.getAcceptanceDeadlineAt().isAfter(twoHours)
                            ? "2h"
                            : "24h";

                    if (agreement.getOwnerAcceptedAt() == null && booking.getCar() != null && booking.getCar().getOwner() != null) {
                        sendReminder(
                                booking.getCar().getOwner().getId(),
                                booking.getId(),
                                bucket,
                                "Ugovor o iznajmljivanju čeka vaše prihvatanje. Prihvatite ugovor pre check-in procesa."
                        );
                    }
                    if (agreement.getRenterAcceptedAt() == null && booking.getRenter() != null) {
                        sendReminder(
                                booking.getRenter().getId(),
                                booking.getId(),
                                bucket,
                                "Ugovor o iznajmljivanju čeka vaše prihvatanje. Prihvatite ugovor pre preuzimanja vozila."
                        );
                    }
                });
            }
        } finally {
            lockService.releaseLock(LOCK_REMINDER);
        }
    }

    public void ensureWorkflowDefaults(RentalAgreement agreement, Booking booking) {
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

    private void backfillMissingDeadlinesAndActors() {
        for (Long agreementId : agreementRepository.findIdsByStatusInAndAcceptanceDeadlineAtIsNull(ACTIVE_AGREEMENT_STATUSES)) {
            try {
                rentalAgreementBackfillProcessor.backfillWorkflowDefaults(agreementId);
            } catch (Exception e) {
                log.error("[Agreement] Failed to backfill workflow defaults for agreement {}: {}", agreementId, e.getMessage(), e);
            }
        }
    }

    private void sendReminder(Long recipientId, Long bookingId, String bucket, String message) {
        try {
            notificationService.createNotification(CreateNotificationRequestDTO.builder()
                    .recipientId(recipientId)
                    .type(NotificationType.CHECK_IN_REMINDER)
                    .message(message)
                    .relatedEntityId("booking-" + bookingId + "-agreement-reminder-" + bucket + "-" + recipientId)
                    .build());
        } catch (Exception e) {
            log.error("[Agreement] Failed to send reminder for booking {} to user {}: {}", bookingId, recipientId, e.getMessage());
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

    private String resolveWorkflowStatus(RentalAgreement agreement, boolean ownerAccepted, boolean renterAccepted) {
        if (agreement.getStatus() == RentalAgreementStatus.EXPIRED) {
            if (agreement.getExpiredDueToActor() == RentalAgreementActor.RENTER) {
                return STATUS_EXPIRED_RENTER;
            }
            if (agreement.getExpiredDueToActor() == RentalAgreementActor.BOTH) {
                return STATUS_EXPIRED_BOTH;
            }
            return STATUS_EXPIRED_OWNER;
        }
        if (agreement.getStatus() == RentalAgreementStatus.FULLY_ACCEPTED) {
            return STATUS_COMPLETE;
        }
        if (!ownerAccepted && !renterAccepted) {
            return STATUS_PENDING_BOTH;
        }
        return ownerAccepted ? STATUS_PENDING_RENTER : STATUS_PENDING_OWNER;
    }

    private String resolveUrgency(LocalDateTime deadline) {
        if (deadline == null) {
            return "NONE";
        }

        LocalDateTime now = LocalDateTime.now(SERBIA_ZONE);
        if (!deadline.isAfter(now)) {
            return "OVERDUE";
        }
        if (!deadline.isAfter(now.plusHours(24))) {
            return "URGENT";
        }
        return "NORMAL";
    }

    private String resolveRecommendedAction(String workflowStatus,
                                            boolean currentActorNeedsAcceptance,
                                            boolean currentActorCanProceedToCheckIn) {
        if (STATUS_LEGACY.equals(workflowStatus)) {
            return ACTION_OPEN_CHECKIN;
        }
        if (currentActorCanProceedToCheckIn) {
            return ACTION_OPEN_CHECKIN;
        }
        if (currentActorNeedsAcceptance) {
            return ACTION_ACCEPT;
        }
        if (STATUS_COMPLETE.equals(workflowStatus)) {
            return ACTION_OPEN_CHECKIN;
        }
        if (workflowStatus.startsWith("AGREEMENT_EXPIRED")) {
            return ACTION_VIEW;
        }
        return ACTION_WAIT;
    }
}