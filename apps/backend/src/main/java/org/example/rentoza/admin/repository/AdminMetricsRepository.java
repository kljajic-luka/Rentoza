package org.example.rentoza.admin.repository;

import org.example.rentoza.admin.entity.AdminMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for admin dashboard metrics.
 * 
 * <p>Provides query methods for:
 * <ul>
 *   <li>Latest snapshot (cached for dashboard)</li>
 *   <li>Historical metrics (for trend charts)</li>
 *   <li>Daily aggregates (for reports)</li>
 *   <li>Cleanup of old records (retention policy)</li>
 * </ul>
 * 
 * @see AdminMetrics
 * @see AdminDashboardService
 */
@Repository
public interface AdminMetricsRepository extends JpaRepository<AdminMetrics, Long> {
    
    /**
     * Get the latest KPI snapshot.
     * Used for dashboard display (cache for 30 seconds).
     * 
     * @return Most recent metrics snapshot
     */
    @Query(value = "SELECT * FROM admin_metrics ORDER BY created_at DESC LIMIT 1",
           nativeQuery = true)
    Optional<AdminMetrics> findLatestSnapshot();
    
    /**
     * Get historical metrics for trend analysis.
     * Used for generating charts and reports.
     * 
     * @param from Start of date range
     * @param to End of date range
     * @return List of metrics snapshots in chronological order
     */
    @Query("SELECT a FROM AdminMetrics a " +
           "WHERE a.createdAt >= :from " +
           "AND a.createdAt <= :to " +
           "ORDER BY a.createdAt ASC")
    List<AdminMetrics> findHistoricalMetrics(
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to);
    
    /**
     * Get metrics created since a specific time.
     * Used for incremental data loading.
     * 
     * @param since Start time
     * @return List of metrics snapshots
     */
    @Query("SELECT a FROM AdminMetrics a " +
           "WHERE a.createdAt >= :since " +
           "ORDER BY a.createdAt ASC")
    List<AdminMetrics> findMetricsSince(@Param("since") LocalDateTime since);
    
    /**
     * Get the latest N metrics snapshots.
     * Used for recent trend display.
     * 
     * @param count Number of snapshots to retrieve
     * @return List of recent metrics (newest first)
     */
    @Query(value = "SELECT * FROM admin_metrics ORDER BY created_at DESC LIMIT :count",
           nativeQuery = true)
    List<AdminMetrics> findLatestSnapshots(@Param("count") int count);
    
    /**
     * Delete metrics older than specified date.
     * Used for retention policy (keep 12 months).
     * 
     * @param cutoffDate Delete records created before this date
     * @return Number of deleted records
     */
    @Modifying
    @Query("DELETE FROM AdminMetrics a WHERE a.createdAt < :cutoffDate")
    int deleteOlderThan(@Param("cutoffDate") LocalDateTime cutoffDate);
    
    /**
     * Count total metrics snapshots.
     * Used for monitoring storage usage.
     * 
     * @return Total snapshot count
     */
    @Query("SELECT COUNT(a) FROM AdminMetrics a")
    Long countAllSnapshots();
}
