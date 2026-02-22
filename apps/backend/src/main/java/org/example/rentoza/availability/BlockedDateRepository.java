package org.example.rentoza.availability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for managing blocked date ranges for cars.
 */
@Repository
public interface BlockedDateRepository extends JpaRepository<BlockedDate, Long> {

    /**
     * Find all blocked date ranges for a specific car.
     * Useful for displaying the owner's calendar and checking availability.
     */
    List<BlockedDate> findByCarIdOrderByStartDateAsc(Long carId);

    /**
     * Find all blocked date ranges for a specific car owned by a specific user.
     * Used for authorization checks to ensure only the owner can modify blocked dates.
     */
    List<BlockedDate> findByCarIdAndOwnerId(Long carId, Long ownerId);

    /**
     * Check if there are any blocked dates overlapping with the given date range for a car.
     * Used for validation when creating new blocked ranges or bookings.
     */
    @Query("SELECT COUNT(b) > 0 FROM BlockedDate b WHERE b.car.id = :carId " +
           "AND b.startDate <= :endDate AND b.endDate >= :startDate")
    boolean existsOverlappingBlockedDates(
            @Param("carId") Long carId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Find all blocked dates that overlap with a specific date range.
     * Useful for detailed overlap detection and error messaging.
     */
    @Query("SELECT b FROM BlockedDate b WHERE b.car.id = :carId " +
           "AND b.startDate <= :endDate AND b.endDate >= :startDate")
    List<BlockedDate> findOverlappingBlockedDates(
            @Param("carId") Long carId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Delete all blocked dates for a specific car (useful for car deletion cascade).
     */
    void deleteByCarId(Long carId);

    /**
     * Raw existence check by booking_id.
     * Used in tests and admin reconciliation to verify if a booking-linked row exists,
     * regardless of the booking's current status.
     */
    @Query(value = "SELECT COUNT(*) > 0 FROM blocked_dates WHERE booking_id = :bookingId", nativeQuery = true)
    boolean existsByBookingId(@Param("bookingId") Long bookingId);

    // =========================================================================
    // Status-aware defensive reads (P0 hardening)
    //
    // Booking-linked rows (booking_id IS NOT NULL) are only treated as "effective"
    // when the linked booking is still in an occupying status.  This ensures that
    // stale rows left by no-show / cancellation / completion transitions do NOT
    // suppress availability even if the trigger (V60) has not yet fired (e.g.
    // during the same transaction or in legacy data).
    //
    // Manual blocks (booking_id IS NULL) are always effective.
    //
    // The occupying-status list here must stay in sync with:
    //   fn_booking_is_occupying() in V60 migration
    //   BlockedDateRepository.OCCUPYING_STATUSES
    // =========================================================================

    /** Canonical occupying statuses — keep in sync with V60 migration helper. */
    String OCCUPYING_STATUSES =
            "'ACTIVE','APPROVED','CHECK_IN_OPEN','CHECK_IN_HOST_COMPLETE'," +
            "'CHECK_IN_COMPLETE','CHECK_IN_DISPUTE','IN_TRIP','CHECKOUT_OPEN'," +
            "'CHECKOUT_GUEST_COMPLETE','CHECKOUT_HOST_COMPLETE'";

    /**
     * Status-aware overlap check.
     * Returns true only if there is an effective block overlapping the range:
     *   - Manual block (booking_id IS NULL), OR
     *   - Booking-linked block whose booking is still in an occupying status.
     */
    @Query(value = """
            SELECT COUNT(*) > 0
            FROM   blocked_dates bd
            WHERE  bd.car_id = :carId
              AND  bd.start_date <= :endDate
              AND  bd.end_date   >= :startDate
              AND  (
                       bd.booking_id IS NULL
                   OR EXISTS (
                       SELECT 1 FROM bookings b
                       WHERE  b.id = bd.booking_id
                         AND  b.status IN (
                                 'ACTIVE','APPROVED','CHECK_IN_OPEN',
                                 'CHECK_IN_HOST_COMPLETE','CHECK_IN_COMPLETE',
                                 'CHECK_IN_DISPUTE','IN_TRIP','CHECKOUT_OPEN',
                                 'CHECKOUT_GUEST_COMPLETE','CHECKOUT_HOST_COMPLETE'
                              )
                   )
              )
            """,
            nativeQuery = true)
    boolean existsEffectiveOverlappingBlockedDates(
            @Param("carId") Long carId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Status-aware calendar fetch.
     * Returns only effective blocks (manual OR booking-linked with occupying status).
     * Used by getUnavailableRanges() and calendar display.
     */
    @Query(value = """
            SELECT bd.*
            FROM   blocked_dates bd
            WHERE  bd.car_id = :carId
              AND  (
                       bd.booking_id IS NULL
                   OR EXISTS (
                       SELECT 1 FROM bookings b
                       WHERE  b.id = bd.booking_id
                         AND  b.status IN (
                                 'ACTIVE','APPROVED','CHECK_IN_OPEN',
                                 'CHECK_IN_HOST_COMPLETE','CHECK_IN_COMPLETE',
                                 'CHECK_IN_DISPUTE','IN_TRIP','CHECKOUT_OPEN',
                                 'CHECKOUT_GUEST_COMPLETE','CHECKOUT_HOST_COMPLETE'
                              )
                   )
              )
            ORDER BY bd.start_date ASC
            """,
            nativeQuery = true)
    List<BlockedDate> findEffectiveByCarIdOrderByStartDateAsc(@Param("carId") Long carId);
}
