package org.example.rentoza.booking.checkin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Repository for check-in event audit trail.
 * 
 * <p><b>IMPORTANT:</b> This table is append-only. All queries are SELECT operations.
 * INSERT is handled via JPA persist, but UPDATE and DELETE are blocked by database triggers.
 *
 * @see CheckInEvent
 */
public interface CheckInEventRepository extends JpaRepository<CheckInEvent, Long> {

    /**
     * Find all events for a booking, ordered by timestamp.
     * Used for audit trail display and dispute resolution.
     */
    @Query("SELECT e FROM CheckInEvent e WHERE e.booking.id = :bookingId ORDER BY e.eventTimestamp ASC")
    List<CheckInEvent> findByBookingIdOrderByEventTimestampAsc(@Param("bookingId") Long bookingId);

    /**
     * Find all events for a check-in session.
     * A session spans from CHECK_IN_OPENED to TRIP_STARTED (or NO_SHOW).
     */
    @Query("SELECT e FROM CheckInEvent e WHERE e.checkInSessionId = :sessionId ORDER BY e.eventTimestamp ASC")
    List<CheckInEvent> findByCheckInSessionIdOrderByEventTimestampAsc(@Param("sessionId") String sessionId);

    /**
     * Find events by type for a booking.
     * Used to check if specific events have occurred (e.g., reminder sent).
     */
    @Query("SELECT e FROM CheckInEvent e WHERE e.booking.id = :bookingId AND e.eventType = :eventType")
    List<CheckInEvent> findByBookingIdAndEventType(
            @Param("bookingId") Long bookingId, 
            @Param("eventType") CheckInEventType eventType);

    @Query("SELECT e FROM CheckInEvent e WHERE e.checkInSessionId = :sessionId AND e.eventType = :eventType ORDER BY e.eventTimestamp ASC")
    List<CheckInEvent> findByCheckInSessionIdAndEventType(
            @Param("sessionId") String sessionId,
            @Param("eventType") CheckInEventType eventType);

    /**
     * Check if a specific event type exists for a booking.
     * More efficient than fetching full entities when only existence is needed.
     */
    @Query("SELECT COUNT(e) > 0 FROM CheckInEvent e WHERE e.booking.id = :bookingId AND e.eventType = :eventType")
    boolean existsByBookingIdAndEventType(
            @Param("bookingId") Long bookingId, 
            @Param("eventType") CheckInEventType eventType);

    /**
     * Find all events by actor (user ID).
     * Used for user activity audit and fraud detection.
     */
    @Query("SELECT e FROM CheckInEvent e WHERE e.actorId = :actorId ORDER BY e.eventTimestamp DESC")
    List<CheckInEvent> findByActorIdOrderByEventTimestampDesc(@Param("actorId") Long actorId);

    /**
     * Find events within a time range.
     * Used for analytics and debugging.
     */
    @Query("SELECT e FROM CheckInEvent e " +
           "WHERE e.eventTimestamp >= :startTime AND e.eventTimestamp <= :endTime " +
           "ORDER BY e.eventTimestamp ASC")
    List<CheckInEvent> findByEventTimestampBetween(
            @Param("startTime") Instant startTime, 
            @Param("endTime") Instant endTime);

    /**
     * Count events by type for a booking.
     * Used for validation (e.g., photo upload count).
     */
    @Query("SELECT COUNT(e) FROM CheckInEvent e WHERE e.booking.id = :bookingId AND e.eventType = :eventType")
    long countByBookingIdAndEventType(
            @Param("bookingId") Long bookingId, 
            @Param("eventType") CheckInEventType eventType);

    /**
     * Find the last event of a specific type for a booking.
     * Used for idempotency checks (e.g., handshake already confirmed?).
     */
    @Query("SELECT e FROM CheckInEvent e " +
           "WHERE e.booking.id = :bookingId AND e.eventType = :eventType " +
           "ORDER BY e.eventTimestamp DESC LIMIT 1")
    CheckInEvent findLastEventByBookingIdAndEventType(
            @Param("bookingId") Long bookingId, 
            @Param("eventType") CheckInEventType eventType);

    /**
     * Find events by actor role (HOST, GUEST, SYSTEM).
     * Used for role-specific activity analysis.
     */
    @Query("SELECT e FROM CheckInEvent e WHERE e.booking.id = :bookingId AND e.actorRole = :actorRole " +
           "ORDER BY e.eventTimestamp ASC")
    List<CheckInEvent> findByBookingIdAndActorRole(
            @Param("bookingId") Long bookingId, 
            @Param("actorRole") CheckInActorRole actorRole);
}
