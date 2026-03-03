package org.example.rentoza.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            BookingStatus.CHECK_IN_OPEN,
            BookingStatus.CHECK_IN_HOST_COMPLETE,
            BookingStatus.CHECK_IN_COMPLETE,
            BookingStatus.IN_TRIP,
            BookingStatus.PENDING_APPROVAL
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

        List<Booking> activeBookings = bookingRepository.findByStatusIn(
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

    public record BackfillResult(int created, int skipped, int errors) {}
}
