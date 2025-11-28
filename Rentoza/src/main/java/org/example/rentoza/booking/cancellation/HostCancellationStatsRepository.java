package org.example.rentoza.booking.cancellation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link HostCancellationStats} entities.
 * 
 * <p>Provides access to host cancellation tracking data for penalty tier
 * calculation, suspension management, and analytics.
 * 
 * <p><b>Concurrency Note:</b> Use {@link #findByIdForUpdate(Long)} when
 * incrementing cancellation counts to prevent race conditions.
 * 
 * @since 2024-01 (Cancellation Policy Migration - Phase 1)
 */
public interface HostCancellationStatsRepository extends JpaRepository<HostCancellationStats, Long> {

    // ==================== BASIC LOOKUPS ====================

    /**
     * Find stats by host ID (same as user ID).
     * Alias for findById() with more descriptive name.
     * 
     * @param hostId Host user ID
     * @return Optional containing stats if exists
     */
    default Optional<HostCancellationStats> findByHostId(Long hostId) {
        return findById(hostId);
    }

    /**
     * Check if stats exist for a host.
     * 
     * @param hostId Host user ID
     * @return true if stats record exists
     */
    default boolean existsByHostId(Long hostId) {
        return existsById(hostId);
    }

    // ==================== SUSPENSION QUERIES ====================

    /**
     * Find all hosts currently under suspension.
     * Used for admin monitoring and reporting.
     * 
     * @param now Current timestamp
     * @return List of suspended hosts
     */
    @Query("SELECT hcs FROM HostCancellationStats hcs " +
           "WHERE hcs.suspensionEndsAt IS NOT NULL AND hcs.suspensionEndsAt > :now " +
           "ORDER BY hcs.suspensionEndsAt ASC")
    List<HostCancellationStats> findCurrentlySuspendedHosts(@Param("now") LocalDateTime now);

    /**
     * Find hosts whose suspension is about to end (within N hours).
     * Used for notification triggers.
     * 
     * @param from Start of window
     * @param to End of window
     * @return List of hosts with expiring suspensions
     */
    @Query("SELECT hcs FROM HostCancellationStats hcs " +
           "WHERE hcs.suspensionEndsAt BETWEEN :from AND :to")
    List<HostCancellationStats> findSuspensionsEndingBetween(
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to
    );

    // ==================== PATTERN DETECTION ====================

    /**
     * Find hosts with excessive cancellations in the last 30 days.
     * Threshold: 3+ cancellations triggers account review.
     * 
     * @param threshold Minimum cancellation count
     * @return List of hosts exceeding threshold
     */
    @Query("SELECT hcs FROM HostCancellationStats hcs " +
           "WHERE hcs.cancellationsLast30Days >= :threshold " +
           "ORDER BY hcs.cancellationsLast30Days DESC")
    List<HostCancellationStats> findHostsExceedingThreshold30Days(@Param("threshold") int threshold);

    /**
     * Find hosts with high cancellation rate.
     * Rate > 5% may result in reduced search visibility.
     * 
     * @param rateThreshold Percentage threshold (e.g., 5.0 for 5%)
     * @return List of hosts exceeding rate threshold
     */
    @Query("SELECT hcs FROM HostCancellationStats hcs " +
           "WHERE hcs.cancellationRate > :rateThreshold " +
           "AND hcs.totalBookings >= 10 " +  // Minimum sample size
           "ORDER BY hcs.cancellationRate DESC")
    List<HostCancellationStats> findHostsWithHighCancellationRate(
        @Param("rateThreshold") java.math.BigDecimal rateThreshold
    );

    // ==================== SCHEDULED JOB QUERIES ====================

    /**
     * Reset yearly cancellation counts.
     * Called by scheduler on January 1st.
     * 
     * @return Number of records updated
     */
    @Modifying
    @Query("UPDATE HostCancellationStats hcs " +
           "SET hcs.cancellationsThisYear = 0, hcs.penaltyTier = 0")
    int resetYearlyCounts();

    /**
     * Decrement 30-day rolling count for cancellations older than 30 days.
     * Called by daily scheduler.
     * 
     * @param threshold 30 days ago timestamp
     * @return Number of records updated
     */
    @Modifying
    @Query("UPDATE HostCancellationStats hcs " +
           "SET hcs.cancellationsLast30Days = GREATEST(0, hcs.cancellationsLast30Days - 1) " +
           "WHERE hcs.lastCancellationAt <= :threshold AND hcs.cancellationsLast30Days > 0")
    int decrementExpired30DayCounts(@Param("threshold") LocalDateTime threshold);

    // ==================== ANALYTICS ====================

    /**
     * Get distribution of hosts by penalty tier.
     * 
     * @return List of [tier, count] pairs
     */
    @Query("SELECT hcs.penaltyTier, COUNT(hcs) FROM HostCancellationStats hcs " +
           "GROUP BY hcs.penaltyTier " +
           "ORDER BY hcs.penaltyTier")
    List<Object[]> countByPenaltyTier();

    /**
     * Find top offenders (highest cancellation count this year).
     * 
     * @param limit Maximum number of results
     * @return List of highest-cancellation hosts
     */
    @Query("SELECT hcs FROM HostCancellationStats hcs " +
           "WHERE hcs.cancellationsThisYear > 0 " +
           "ORDER BY hcs.cancellationsThisYear DESC")
    List<HostCancellationStats> findTopOffenders(@Param("limit") int limit);

    // ==================== CONCURRENCY SAFE OPERATIONS ====================

    /**
     * Find stats with pessimistic write lock for safe increment.
     * Use this when updating cancellation counts to prevent race conditions.
     * 
     * @param hostId Host user ID
     * @return Optional with locked record
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT hcs FROM HostCancellationStats hcs WHERE hcs.hostId = :hostId")
    Optional<HostCancellationStats> findByIdForUpdate(@Param("hostId") Long hostId);
}
