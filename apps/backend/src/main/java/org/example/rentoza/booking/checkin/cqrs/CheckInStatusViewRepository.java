package org.example.rentoza.booking.checkin.cqrs;

import org.example.rentoza.booking.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for CheckInStatusView read model.
 * 
 * <h2>Query Optimization</h2>
 * <p>All queries leverage the indexes defined on the entity:
 * <ul>
 *   <li>idx_csv_booking_id - unique booking lookup</li>
 *   <li>idx_csv_host_user / idx_csv_guest_user - user dashboard queries</li>
 *   <li>idx_csv_status - status filtering</li>
 *   <li>idx_csv_status_noshow - no-show deadline queries</li>
 * </ul>
 * 
 * @see CheckInStatusView for entity definition
 * @see CheckInStatusViewSyncListener for write operations
 */
@Repository
public interface CheckInStatusViewRepository extends JpaRepository<CheckInStatusView, Long>, CheckInStatusViewRepositoryCustom {

    // ========== SINGLE BOOKING QUERIES ==========

    /**
     * Find view by booking ID.
     */
    Optional<CheckInStatusView> findByBookingId(Long bookingId);

    /**
     * Find view by session ID.
     */
    Optional<CheckInStatusView> findBySessionId(UUID sessionId);

    /**
     * Check if view exists for booking.
     */
    boolean existsByBookingId(Long bookingId);

    // ========== USER DASHBOARD QUERIES ==========

    /**
     * Find active check-ins where user is host.
     * Active = not completed trip and not no-show.
     */
    @Query("""
        SELECT v FROM CheckInStatusView v
        WHERE v.hostUserId = :userId
        AND v.tripStarted = false
        AND v.noShowParty IS NULL
        ORDER BY v.scheduledStartTime ASC
        """)
    List<CheckInStatusView> findActiveCheckInsForHost(@Param("userId") Long userId);

    /**
     * Find active check-ins where user is guest.
     */
    @Query("""
        SELECT v FROM CheckInStatusView v
        WHERE v.guestUserId = :userId
        AND v.tripStarted = false
        AND v.noShowParty IS NULL
        ORDER BY v.scheduledStartTime ASC
        """)
    List<CheckInStatusView> findActiveCheckInsForGuest(@Param("userId") Long userId);

    /**
     * Find all check-ins for user (both host and guest roles).
     */
    @Query("""
        SELECT v FROM CheckInStatusView v
        WHERE v.hostUserId = :userId OR v.guestUserId = :userId
        ORDER BY v.scheduledStartTime DESC
        """)
    List<CheckInStatusView> findAllCheckInsForUser(@Param("userId") Long userId);

    /**
     * Find actionable check-ins for host (needing host action).
     */
    @Query("""
        SELECT v FROM CheckInStatusView v
        WHERE v.hostUserId = :userId
        AND v.status = 'CHECK_IN_OPEN'
        AND v.hostCheckInComplete = false
        ORDER BY v.scheduledStartTime ASC
        """)
    List<CheckInStatusView> findActionableForHost(@Param("userId") Long userId);

    /**
     * Find actionable check-ins for guest (needing guest action).
     */
    @Query("""
        SELECT v FROM CheckInStatusView v
        WHERE v.guestUserId = :userId
        AND (
            (v.status = 'CHECK_IN_HOST_COMPLETE' AND v.guestCheckInComplete = false)
            OR (v.status = 'CHECK_IN_COMPLETE' AND v.handshakeComplete = false)
        )
        ORDER BY v.scheduledStartTime ASC
        """)
    List<CheckInStatusView> findActionableForGuest(@Param("userId") Long userId);

    // ========== STATUS-BASED QUERIES ==========

    /**
     * Find views by status.
     */
    List<CheckInStatusView> findByStatus(BookingStatus status);

    /**
     * Find views approaching no-show deadline.
     */
    @Query("""
        SELECT v FROM CheckInStatusView v
        WHERE v.status IN ('CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE')
        AND v.noShowDeadline IS NOT NULL
        AND v.noShowDeadline <= :deadline
        AND v.noShowParty IS NULL
        ORDER BY v.noShowDeadline ASC
        """)
    List<CheckInStatusView> findApproachingNoShowDeadline(@Param("deadline") LocalDateTime deadline);

    /**
     * Find stale views (not synced recently).
     */
    @Query("""
        SELECT v FROM CheckInStatusView v
        WHERE v.lastSyncAt < :threshold
        AND v.tripStarted = false
        AND v.noShowParty IS NULL
        ORDER BY v.lastSyncAt ASC
        """)
    List<CheckInStatusView> findStaleViews(@Param("threshold") Instant threshold);

    // ========== BULK UPDATE QUERIES ==========

    /**
     * Update host completion status.
     */
    @Modifying
    @Query("""
        UPDATE CheckInStatusView v
        SET v.hostCheckInComplete = true,
            v.hostCompletedAt = :completedAt,
            v.odometerReading = :odometer,
            v.fuelLevelPercent = :fuel,
            v.status = :status,
            v.statusDisplay = :statusDisplay,
            v.lastSyncAt = :syncAt
        WHERE v.bookingId = :bookingId
        """)
    int updateHostCompletion(
            @Param("bookingId") Long bookingId,
            @Param("completedAt") Instant completedAt,
            @Param("odometer") Integer odometer,
            @Param("fuel") Integer fuel,
            @Param("status") BookingStatus status,
            @Param("statusDisplay") String statusDisplay,
            @Param("syncAt") Instant syncAt);

    /**
     * Update guest completion status.
     */
    @Modifying
    @Query("""
        UPDATE CheckInStatusView v
        SET v.guestCheckInComplete = true,
            v.guestCompletedAt = :completedAt,
            v.status = :status,
            v.statusDisplay = :statusDisplay,
            v.lastSyncAt = :syncAt
        WHERE v.bookingId = :bookingId
        """)
    int updateGuestCompletion(
            @Param("bookingId") Long bookingId,
            @Param("completedAt") Instant completedAt,
            @Param("status") BookingStatus status,
            @Param("statusDisplay") String statusDisplay,
            @Param("syncAt") Instant syncAt);

    /**
     * Update trip started status.
     */
    @Modifying
    @Query("""
        UPDATE CheckInStatusView v
        SET v.handshakeComplete = true,
            v.tripStarted = true,
            v.handshakeCompletedAt = :completedAt,
            v.tripStartedAt = :tripStartedAt,
            v.handshakeMethod = :method,
            v.status = :status,
            v.statusDisplay = :statusDisplay,
            v.lastSyncAt = :syncAt
        WHERE v.bookingId = :bookingId
        """)
    int updateTripStarted(
            @Param("bookingId") Long bookingId,
            @Param("completedAt") Instant completedAt,
            @Param("tripStartedAt") Instant tripStartedAt,
            @Param("method") String method,
            @Param("status") BookingStatus status,
            @Param("statusDisplay") String statusDisplay,
            @Param("syncAt") Instant syncAt);

    /**
     * Update no-show status.
     */
    @Modifying
    @Query("""
        UPDATE CheckInStatusView v
        SET v.noShowParty = :party,
            v.status = :status,
            v.statusDisplay = :statusDisplay,
            v.lastSyncAt = :syncAt
        WHERE v.bookingId = :bookingId
        """)
    int updateNoShow(
            @Param("bookingId") Long bookingId,
            @Param("party") String party,
            @Param("status") BookingStatus status,
            @Param("statusDisplay") String statusDisplay,
            @Param("syncAt") Instant syncAt);

    /**
     * Update photo count.
     */
    @Modifying
    @Query("""
        UPDATE CheckInStatusView v
        SET v.photoCount = :count,
            v.lastSyncAt = :syncAt
        WHERE v.bookingId = :bookingId
        """)
    int updatePhotoCount(
            @Param("bookingId") Long bookingId,
            @Param("count") Integer count,
            @Param("syncAt") Instant syncAt);

    /**
     * Update lockbox availability.
     */
    @Modifying
    @Query("""
        UPDATE CheckInStatusView v
        SET v.lockboxAvailable = :available,
            v.lastSyncAt = :syncAt
        WHERE v.bookingId = :bookingId
        """)
    int updateLockboxAvailable(
            @Param("bookingId") Long bookingId,
            @Param("available") boolean available,
            @Param("syncAt") Instant syncAt);

    // ========== CLEANUP QUERIES ==========

    /**
     * Delete views for completed trips older than threshold.
     */
    @Modifying
    @Query("""
        DELETE FROM CheckInStatusView v
        WHERE v.tripStarted = true
        AND v.tripStartedAt < :threshold
        """)
    int deleteCompletedOlderThan(@Param("threshold") Instant threshold);

    /**
     * Delete views for no-shows older than threshold.
     */
    @Modifying
    @Query("""
        DELETE FROM CheckInStatusView v
        WHERE v.noShowParty IS NOT NULL
        AND v.lastSyncAt < :threshold
        """)
    int deleteNoShowsOlderThan(@Param("threshold") Instant threshold);

}
