package org.example.rentoza.booking.checkin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repository for photo discrepancies between host and guest photos.
 * 
 * <p>Used for tracking and resolving differences detected during
 * dual-party photo capture at check-in and checkout.
 *
 * @see PhotoDiscrepancy
 */
public interface PhotoDiscrepancyRepository extends JpaRepository<PhotoDiscrepancy, Long> {

    /**
     * Find all discrepancies for a booking.
     */
    @Query("SELECT d FROM PhotoDiscrepancy d WHERE d.booking.id = :bookingId ORDER BY d.severity DESC, d.createdAt DESC")
    List<PhotoDiscrepancy> findByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Find discrepancies by type for a booking (CHECK_IN or CHECK_OUT).
     */
    @Query("SELECT d FROM PhotoDiscrepancy d " +
           "WHERE d.booking.id = :bookingId AND d.discrepancyType = :type " +
           "ORDER BY d.severity DESC, d.createdAt DESC")
    List<PhotoDiscrepancy> findByBookingIdAndType(
            @Param("bookingId") Long bookingId,
            @Param("type") PhotoDiscrepancy.DiscrepancyType type);

    /**
     * Find pending discrepancies for a booking.
     */
    @Query("SELECT d FROM PhotoDiscrepancy d " +
           "WHERE d.booking.id = :bookingId AND d.resolutionStatus = 'PENDING' " +
           "ORDER BY d.severity DESC")
    List<PhotoDiscrepancy> findPendingByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Find unresolved discrepancies blocking handover.
     * Returns CRITICAL severity discrepancies that are still PENDING.
     */
    @Query("SELECT d FROM PhotoDiscrepancy d " +
           "WHERE d.booking.id = :bookingId " +
           "AND d.severity = 'CRITICAL' " +
           "AND d.resolutionStatus = 'PENDING'")
    List<PhotoDiscrepancy> findBlockingDiscrepancies(@Param("bookingId") Long bookingId);

    /**
     * Count pending discrepancies by booking and type.
     */
    @Query("SELECT COUNT(d) FROM PhotoDiscrepancy d " +
           "WHERE d.booking.id = :bookingId " +
           "AND d.discrepancyType = :type " +
           "AND d.resolutionStatus = 'PENDING'")
    long countPendingByBookingIdAndType(
            @Param("bookingId") Long bookingId,
            @Param("type") PhotoDiscrepancy.DiscrepancyType type);

    /**
     * Count discrepancies by severity for a booking.
     */
    @Query("SELECT COUNT(d) FROM PhotoDiscrepancy d " +
           "WHERE d.booking.id = :bookingId AND d.severity = :severity")
    long countByBookingIdAndSeverity(
            @Param("bookingId") Long bookingId,
            @Param("severity") PhotoDiscrepancy.Severity severity);

    /**
     * Find all pending discrepancies across all bookings (for admin dashboard).
     */
    @Query("SELECT d FROM PhotoDiscrepancy d " +
           "WHERE d.resolutionStatus = 'PENDING' " +
           "ORDER BY d.severity DESC, d.createdAt ASC")
    List<PhotoDiscrepancy> findAllPending();

    /**
     * Find pending discrepancies requiring immediate attention.
     * Returns HIGH and CRITICAL severity discrepancies.
     */
    @Query("SELECT d FROM PhotoDiscrepancy d " +
           "WHERE d.resolutionStatus = 'PENDING' " +
           "AND d.severity IN ('HIGH', 'CRITICAL') " +
           "ORDER BY d.severity DESC, d.createdAt ASC")
    List<PhotoDiscrepancy> findUrgentPending();

    /**
     * Find discrepancies resolved by a specific admin.
     */
    @Query("SELECT d FROM PhotoDiscrepancy d " +
           "WHERE d.resolvedBy.id = :adminId " +
           "ORDER BY d.resolvedAt DESC")
    List<PhotoDiscrepancy> findResolvedByAdmin(@Param("adminId") Long adminId);

    /**
     * Check if any critical discrepancies exist for a booking.
     */
    @Query("SELECT CASE WHEN COUNT(d) > 0 THEN true ELSE false END " +
           "FROM PhotoDiscrepancy d " +
           "WHERE d.booking.id = :bookingId " +
           "AND d.severity = 'CRITICAL' " +
           "AND d.resolutionStatus = 'PENDING'")
    boolean hasCriticalPendingDiscrepancies(@Param("bookingId") Long bookingId);

    /**
     * Find discrepancies by photo type for comparison analysis.
     */
    @Query("SELECT d FROM PhotoDiscrepancy d " +
           "WHERE d.booking.id = :bookingId AND d.photoType = :photoType " +
           "ORDER BY d.createdAt DESC")
    List<PhotoDiscrepancy> findByBookingIdAndPhotoType(
            @Param("bookingId") Long bookingId,
            @Param("photoType") CheckInPhotoType photoType);

    /**
     * Count total discrepancies for metrics/analytics.
     */
    @Query("SELECT COUNT(d) FROM PhotoDiscrepancy d " +
           "WHERE d.discrepancyType = :type " +
           "AND d.createdAt >= :since")
    long countByTypeSince(
            @Param("type") PhotoDiscrepancy.DiscrepancyType type,
            @Param("since") java.time.Instant since);

    /**
     * Find AI-detected discrepancies (future enhancement).
     */
    @Query("SELECT d FROM PhotoDiscrepancy d " +
           "WHERE d.aiConfidenceScore IS NOT NULL " +
           "ORDER BY d.aiConfidenceScore DESC")
    List<PhotoDiscrepancy> findAiDetected();
}
