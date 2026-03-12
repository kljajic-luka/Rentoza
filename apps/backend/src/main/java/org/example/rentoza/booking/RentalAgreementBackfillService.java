package org.example.rentoza.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * One-time backfill service to create rental agreements for existing
 * future/active bookings that lack one.
 *
 * <p>Never overwrites existing agreements. Safe to run multiple times.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RentalAgreementBackfillService {

    private static final Set<BookingStatus> BACKFILL_ELIGIBLE_STATUSES = Set.of(
            BookingStatus.ACTIVE,
            BookingStatus.APPROVED,
            BookingStatus.CHECK_IN_OPEN,
            BookingStatus.CHECK_IN_HOST_COMPLETE,
            BookingStatus.CHECK_IN_COMPLETE,
            BookingStatus.CHECK_IN_DISPUTE,
            BookingStatus.IN_TRIP,
            BookingStatus.CHECKOUT_OPEN,
            BookingStatus.CHECKOUT_GUEST_COMPLETE,
            BookingStatus.CHECKOUT_HOST_COMPLETE,
            BookingStatus.CHECKOUT_SETTLEMENT_PENDING,
            BookingStatus.CHECKOUT_DAMAGE_DISPUTE
    );

    private final BookingRepository bookingRepository;
    private final RentalAgreementRepository agreementRepository;
    private final RentalAgreementService agreementService;

    /**
     * Backfill agreements for all eligible bookings that don't have one.
     *
     * @return count of agreements created
     */
    public BackfillResult backfillAgreements() {
        log.info("[Backfill] Starting rental agreement backfill...");

        List<Booking> activeBookings = bookingRepository.findByStatusInWithRelations(
                BACKFILL_ELIGIBLE_STATUSES.stream().toList());

        if (activeBookings.isEmpty()) {
            log.info("[Backfill] No eligible bookings found");
            return new BackfillResult(0, 0, 0);
        }

        // Find which bookings already have agreements
        List<Long> bookingIds = activeBookings.stream().map(Booking::getId).toList();
        List<Long> existingIds = agreementRepository.findBookingIdsWithAgreements(bookingIds);

        int created = 0;
        int skipped = 0;
        int errors = 0;

        for (Booking booking : activeBookings) {
            if (existingIds.contains(booking.getId())) {
                skipped++;
                continue;
            }

            try {
                agreementService.generateAgreement(booking);
                created++;
            } catch (Exception e) {
                errors++;
                log.error("[Backfill] Failed to create agreement for booking {}: {}",
                        booking.getId(), e.getMessage());
            }
        }

        log.info("[Backfill] Complete: created={}, skipped={}, errors={}", created, skipped, errors);
        return new BackfillResult(created, skipped, errors);
    }

    /**
     * Scheduled self-heal: backfill agreements for any eligible booking
     * that is missing one. Runs every 6 hours. Safe to run repeatedly
     * (idempotent — never overwrites existing agreements).
     */
    @Scheduled(cron = "${app.compliance.rental-agreement.backfill-cron:0 0 */6 * * *}")
    public void scheduledBackfill() {
        log.info("[Backfill] Scheduled rental agreement backfill triggered");
        BackfillResult result = backfillAgreements();
        if (result.created() > 0 || result.errors() > 0) {
            log.warn("[Backfill] Scheduled backfill result: created={}, skipped={}, errors={}",
                    result.created(), result.skipped(), result.errors());
        }
    }

    public record BackfillResult(int created, int skipped, int errors) {}
}
