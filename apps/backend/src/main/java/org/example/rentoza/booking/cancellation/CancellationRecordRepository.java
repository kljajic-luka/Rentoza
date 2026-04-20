package org.example.rentoza.booking.cancellation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link CancellationRecord} entities.
 * 
 * <p>Provides access to cancellation audit records with specialized queries
 * for analytics, dispute resolution, and idempotency checks.
 * 
 * @since 2024-01 (Cancellation Policy Migration - Phase 1)
 */
public interface CancellationRecordRepository extends JpaRepository<CancellationRecord, Long> {

    // ==================== IDEMPOTENCY CHECKS ====================

    /**
     * Check if a cancellation record already exists for a booking.
     * Used to prevent double-cancellation attempts.
     * 
     * @param bookingId Booking ID to check
     * @return true if a cancellation record exists
     */
    boolean existsByBookingId(Long bookingId);

    /**
     * Find cancellation record by booking ID.
     * 
     * @param bookingId Booking ID
     * @return Optional containing the record if exists
     */
    Optional<CancellationRecord> findByBookingId(Long bookingId);

    // ==================== ANALYTICS QUERIES ====================

    /**
     * Find all cancellation records for a specific initiator type.
     * Used for analytics dashboards.
     * 
     * @param cancelledBy Party type (GUEST, HOST, SYSTEM)
     * @return List of matching records
     */
    List<CancellationRecord> findByCancelledBy(CancelledBy cancelledBy);

    /**
     * Find all cancellations initiated within a date range.
     * Used for reporting and trend analysis.
     * 
     * @param start Start of date range (inclusive)
     * @param end End of date range (exclusive)
     * @return List of cancellations in the period
     */
    @Query("SELECT cr FROM CancellationRecord cr " +
           "WHERE cr.initiatedAt >= :start AND cr.initiatedAt < :end " +
           "ORDER BY cr.initiatedAt DESC")
    List<CancellationRecord> findByInitiatedAtBetween(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );

    /**
     * Find cancellations by a specific host (via booking's car owner).
     * Used for host performance monitoring.
     * 
     * @param hostId Host user ID
     * @return List of host-initiated cancellations
     */
    @Query("SELECT cr FROM CancellationRecord cr " +
           "JOIN cr.booking b " +
           "JOIN b.car c " +
           "WHERE c.owner.id = :hostId AND cr.cancelledBy = 'HOST' " +
           "ORDER BY cr.initiatedAt DESC")
    List<CancellationRecord> findHostCancellationsByHostId(@Param("hostId") Long hostId);

    /**
     * Find cancellations for a specific guest (renter).
     * Used for guest behavior analysis.
     * 
     * @param renterId Guest user ID
     * @return List of guest-initiated cancellations
     */
    @Query("SELECT cr FROM CancellationRecord cr " +
           "JOIN cr.booking b " +
           "WHERE b.renter.id = :renterId AND cr.cancelledBy = 'GUEST' " +
           "ORDER BY cr.initiatedAt DESC")
    List<CancellationRecord> findGuestCancellationsByRenterId(@Param("renterId") Long renterId);

    // ==================== WAIVER MANAGEMENT ====================

    /**
     * Find all pending waiver requests for admin review.
     * 
     * @return List of records with pending waiver requests
     */
    @Query("SELECT cr FROM CancellationRecord cr " +
           "WHERE cr.penaltyWaiverRequested = true AND cr.penaltyWaiverApproved = false " +
           "ORDER BY cr.initiatedAt ASC")
    List<CancellationRecord> findPendingWaiverRequests();

    // ==================== REFUND PROCESSING ====================

    /**
     * Find all records with pending refunds.
     * Used by refund processing job.
     * 
     * @return List of records awaiting refund processing
     */
    List<CancellationRecord> findByRefundStatus(RefundStatus refundStatus);

    /**
     * Find records with failed refunds for retry.
     * 
     * @return List of failed refund records
     */
    @Query("SELECT cr FROM CancellationRecord cr " +
           "WHERE cr.refundStatus = 'FAILED' " +
           "ORDER BY cr.processedAt ASC")
    List<CancellationRecord> findFailedRefunds();

    /**
     * Find FAILED records that are eligible for retry right now.
     * Conditions:
     * <ul>
     *   <li>refundStatus = FAILED (not yet promoted to MANUAL_REVIEW)</li>
     *   <li>retryCount &lt; maxRetries — attempts remaining</li>
     *   <li>nextRetryAt is null OR nextRetryAt &lt;= :now — backoff window elapsed</li>
     * </ul>
     */
    @Query("""
           SELECT cr FROM CancellationRecord cr
           WHERE cr.refundStatus = 'FAILED'
             AND cr.retryCount < cr.maxRetries
             AND (cr.nextRetryAt IS NULL OR cr.nextRetryAt <= :now)
           ORDER BY cr.processedAt ASC
           """)
    List<CancellationRecord> findRetryEligibleFailed(
            @org.springframework.data.repository.query.Param("now") java.time.Instant now);

    /**
     * Find PROCESSING records that are stale (started but never resolved, likely scheduler crash).
     * These should be re-attempted or escalated.
     */
    @Query("""
           SELECT cr FROM CancellationRecord cr
           WHERE cr.refundStatus = 'PROCESSING'
             AND cr.lastRetryAt < :staleBefore
           ORDER BY cr.lastRetryAt ASC
           """)
    List<CancellationRecord> findStaleProcessing(
            @org.springframework.data.repository.query.Param("staleBefore") java.time.Instant staleBefore);

    // ==================== DISPUTE RESOLUTION ====================

    /**
     * Find cancellation record with full booking details for dispute resolution.
     * Eagerly loads booking, car, renter, and owner.
     * 
     * @param id CancellationRecord ID
     * @return Optional containing fully loaded record
     */
    @Query("SELECT cr FROM CancellationRecord cr " +
           "JOIN FETCH cr.booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner o " +
           "WHERE cr.id = :id")
    Optional<CancellationRecord> findByIdWithFullDetails(@Param("id") Long id);

    /**
     * Count cancellations by reason for the given period.
     * Used for analytics and policy evaluation.
     * 
     * @param start Start of period
     * @param end End of period
     * @return Count grouped by reason
     */
    @Query("SELECT cr.reason, COUNT(cr) FROM CancellationRecord cr " +
           "WHERE cr.initiatedAt >= :start AND cr.initiatedAt < :end " +
           "GROUP BY cr.reason")
    List<Object[]> countByReasonBetween(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );
}
