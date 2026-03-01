package org.example.rentoza.booking.photo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for PhotoAccessLog audit trail.
 * 
 * Queries for:
 * - Compliance audits (who accessed what)
 * - Security analysis (suspicious patterns)
 * - Dispute evidence (access history)
 */
@Repository
public interface PhotoAccessLogRepository extends JpaRepository<PhotoAccessLog, Long> {

    /**
     * Find all access logs for a user.
     */
    Page<PhotoAccessLog> findByUserIdOrderByAccessedAtDesc(Long userId, Pageable pageable);

    /**
     * Find all access logs for a booking.
     */
    Page<PhotoAccessLog> findByBookingIdOrderByAccessedAtDesc(Long bookingId, Pageable pageable);

    /**
     * Find denied access attempts (security audit) within a time window.
     */
    @Query("SELECT log FROM PhotoAccessLog log WHERE log.accessGranted = false AND log.accessedAt >= :since ORDER BY log.accessedAt DESC")
    Page<PhotoAccessLog> findDeniedAccess(@Param("since") Instant since, Pageable pageable);

    /**
     * Find suspicious access patterns: many requests from single IP in short time.
     */
    @Query("SELECT log FROM PhotoAccessLog log " +
           "WHERE log.ipAddress = :ipAddress " +
           "AND log.accessedAt >= :since " +
           "ORDER BY log.accessedAt DESC")
    List<PhotoAccessLog> findAccessesByIp(@Param("ipAddress") String ipAddress, 
                                           @Param("since") Instant since);

    /**
     * Find high-volume access by single user.
     */
    @Query("SELECT log FROM PhotoAccessLog log " +
           "WHERE log.user.id = :userId " +
           "AND log.accessedAt >= :since " +
           "AND log.accessGranted = true " +
           "ORDER BY log.accessedAt DESC")
    List<PhotoAccessLog> findAccessesByUser(@Param("userId") Long userId, 
                                            @Param("since") Instant since);

    /**
     * Find all rate limit violations within a time window.
     */
    @Query("SELECT log FROM PhotoAccessLog log " +
           "WHERE log.httpStatusCode = 429 " +
           "AND log.accessedAt >= :since " +
           "ORDER BY log.accessedAt DESC")
    Page<PhotoAccessLog> findRateLimitViolations(@Param("since") Instant since, Pageable pageable);

    /**
     * Count access attempts in time window (for rate limit analysis).
     */
    @Query("SELECT COUNT(log) FROM PhotoAccessLog log " +
           "WHERE log.user.id = :userId " +
           "AND log.accessedAt >= :since")
    long countAccessesByUserSince(@Param("userId") Long userId, @Param("since") Instant since);
}
