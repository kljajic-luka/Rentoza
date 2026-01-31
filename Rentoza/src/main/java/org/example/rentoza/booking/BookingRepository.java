package org.example.rentoza.booking;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.example.rentoza.car.Car;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    @Query("SELECT b FROM Booking b JOIN FETCH b.renter JOIN FETCH b.car WHERE b.car.id = :carId")
    List<Booking> findByCarId(@Param("carId") Long carId);
    List<Booking> findByRenterEmailIgnoreCase(String email);

    @Query("SELECT b FROM Booking b JOIN FETCH b.renter JOIN FETCH b.car WHERE b.car = :car")
    List<Booking> findByCar(@Param("car") Car car);

    /**
     * Find bookings for a car that overlap with a given time range.
     * Uses the interval overlap formula: (A.start < B.end) AND (A.end > B.start)
     */
    @Query("SELECT b FROM Booking b WHERE b.car.id = :carId " +
           "AND b.startTime < :endTime AND b.endTime > :startTime")
    List<Booking> findByCarIdAndTimeRange(
            @Param("carId") Long carId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    List<Booking> findByCarIdAndRenterEmailAndStatusIn(
            Long carId,
            String renterEmail,
            List<BookingStatus> statuses
    );
    List<Booking> findByCarIdAndRenterEmailIgnoreCaseAndStatusIn(
            Long carId,
            String renterEmail,
            List<BookingStatus> statuses
    );

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.renter.id = :renterId AND b.status = :status")
    long countByRenterIdAndStatus(@Param("renterId") Long renterId, @Param("status") BookingStatus status);

    @Query("SELECT COUNT(b) FROM Booking b WHERE b.car.owner.id = :ownerId AND b.status = :status")
    long countByOwnerIdAndStatus(
            @Param("ownerId") Long ownerId,
            @Param("status") BookingStatus status
    );

    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner " +
           "WHERE r.id = :userId " +
           "ORDER BY b.startTime DESC")
    List<Booking> findByRenterIdWithDetails(@Param("userId") Long userId);

    /**
     * Fetch all bookings for a list of cars in a single query to avoid N+1 problem
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "WHERE c.id IN :carIds")
    List<Booking> findByCarIdIn(@Param("carIds") List<Long> carIds);

    /**
     * Fetch all bookings for cars owned by a specific user
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "WHERE c.owner.id = :ownerId")
    List<Booking> findByCarOwnerIdWithDetails(@Param("ownerId") Long ownerId);

    /**
     * Find booking by ID with all related entities eagerly fetched to prevent lazy-loading issues
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner " +
           "WHERE b.id = :id")
    java.util.Optional<Booking> findByIdWithRelations(@Param("id") Long id);

    /**
     * P0-4 FIX: Find booking by ID with PESSIMISTIC_WRITE lock for race condition prevention.
     * 
     * <p>Used in HostCheckoutPhotoService to prevent guest from changing booking status
     * while host is uploading checkout photos. Lock is held until transaction commits.
     * 
     * <p><b>Critical for checkout photo upload atomicity:</b>
     * <ol>
     *   <li>Host acquires lock on booking row</li>
     *   <li>Verifies status is CHECKOUT_GUEST_COMPLETE</li>
     *   <li>Processes photos under lock</li>
     *   <li>Updates status to CHECKOUT_HOST_COMPLETE</li>
     *   <li>Releases lock on transaction commit</li>
     * </ol>
     * 
     * <p>If guest tries to change status during this window, they will block until
     * host transaction commits, preventing race conditions.
     * 
     * @param id Booking ID to lock
     * @return Booking with pessimistic write lock acquired
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner " +
           "WHERE b.id = :id")
    java.util.Optional<Booking> findByIdWithLock(@Param("id") Long id);

    /**
     * Sum total revenue from completed bookings within a time period.
     * Used for admin dashboard revenue calculations.
     * 
     * @param start Period start (inclusive)
     * @param end Period end (exclusive)
     * @return Total revenue in BigDecimal, or 0 if no bookings
     */
    @Query("SELECT COALESCE(SUM(b.totalPrice), 0) FROM Booking b " +
           "WHERE b.status = 'COMPLETED' AND b.updatedAt BETWEEN :start AND :end")
    java.math.BigDecimal sumTotalAmountByCompletedBookingsInPeriod(
        @Param("start") java.time.Instant start, 
        @Param("end") java.time.Instant end);

    /**
     * Check if there are any active/pending bookings overlapping with the given time range for a car.
     * Used for validation when blocking dates or creating new bookings.
     * 
     * Overlap Formula (Interval Intersection):
     * Two intervals [A_start, A_end) and [B_start, B_end) overlap if:
     * (A_start < B_end) AND (A_end > B_start)
     * 
     * Blocking Statuses:
     * - PENDING_APPROVAL: Awaiting host decision (blocks to prevent overbooking)
     * - ACTIVE: Confirmed, waiting for check-in window
     * - CHECK_IN_OPEN: Check-in window is open (T-24h to T+30m)
     * - CHECK_IN_HOST_COMPLETE: Host completed check-in, awaiting guest
     * - CHECK_IN_COMPLETE: Both parties completed, awaiting handshake
     * - IN_TRIP: Trip is in progress
     * 
     * Non-Blocking Statuses (EXCLUDED):
     * - CANCELLED: Trip was cancelled (times now free)
     * - DECLINED: Host rejected the request
     * - COMPLETED: Trip finished (times now free)
     * - EXPIRED, EXPIRED_SYSTEM: Request expired without action
     * - NO_SHOW_HOST, NO_SHOW_GUEST: No-show scenarios (times freed)
     */
    @Query("SELECT COUNT(b) > 0 FROM Booking b WHERE b.car.id = :carId " +
           "AND b.status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP') " +
           "AND b.startTime < :endTime AND b.endTime > :startTime")
    boolean existsOverlappingBookings(
            @Param("carId") Long carId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * CONCURRENCY HARDENING: Pessimistic locking query for booking creation.
     * 
     * This method acquires an exclusive row-level lock (SELECT ... FOR UPDATE) on all
     * bookings for the given car within the time range. This prevents race conditions
     * where two users could simultaneously pass the availability check and create
     * overlapping bookings.
     * 
     * Lock Behavior:
     * - PESSIMISTIC_WRITE: Acquires exclusive lock, blocking other transactions
     * - Timeout: 5 seconds (prevents permanent deadlocks)
     * - Scope: Only locks relevant rows using idx_booking_time_overlap index
     * 
     * Usage Pattern:
     * 1. Call this method BEFORE creating a new Booking entity
     * 2. If returns true, throw BookingConflictException
     * 3. If returns false, safe to proceed with booking creation
     * 4. Lock is released when transaction commits/rolls back
     * 
     * @param carId Car ID to check
     * @param startTime Booking start timestamp
     * @param endTime Booking end timestamp
     * @return true if conflicting bookings exist (booking should be rejected)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
        @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000") // 5 second timeout
    })
    @Query("SELECT COUNT(b) > 0 FROM Booking b WHERE b.car.id = :carId " +
           "AND b.status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP') " +
           "AND b.startTime < :endTime AND b.endTime > :startTime")
    boolean existsOverlappingBookingsWithLock(
            @Param("carId") Long carId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * Find all bookings that are overdue (end time in the past) but not yet marked as COMPLETED.
     * Used by scheduled task to auto-complete bookings.
     * 
     * Note: This targets ACTIVE bookings (pre-check-in) and IN_TRIP bookings (during trip).
     * The scheduler should transition IN_TRIP → COMPLETED when trip ends.
     */
    @Query("SELECT b FROM Booking b " +
           "WHERE b.status IN ('ACTIVE', 'IN_TRIP') " +
           "AND b.endTime < :currentTime")
    List<Booking> findOverdueBookings(@Param("currentTime") LocalDateTime currentTime);

    /**
     * Find all blocking bookings for a car that overlap with the given time range.
     * Used for real-time conflict detection before creating a booking.
     * 
     * Returns blocking status bookings (these block times):
     * - PENDING_APPROVAL, ACTIVE, CHECK_IN_OPEN, CHECK_IN_HOST_COMPLETE, CHECK_IN_COMPLETE, IN_TRIP
     * 
     * CANCELLED, DECLINED, COMPLETED, EXPIRED, NO_SHOW bookings are excluded.
     */
    @Query("SELECT b FROM Booking b WHERE b.car.id = :carId " +
           "AND b.status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP') " +
           "AND b.startTime < :endTime AND b.endTime > :startTime")
    List<Booking> findByCarIdAndTimeRangeBlocking(
            @Param("carId") Long carId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    // ========== RLS-ENFORCED QUERIES (Enterprise Security Enhancement) ==========

    /**
     * Find booking by ID with ownership constraint.
     * Returns booking only if the user is the renter OR the car owner.
     * Use this instead of findByIdWithRelations() to enforce RLS.
     * 
     * @param id Booking ID
     * @param userId Authenticated user's ID
     * @return Optional containing booking if user has access
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner o " +
           "WHERE b.id = :id " +
           "AND (r.id = :userId OR o.id = :userId)")
    java.util.Optional<Booking> findByIdForUser(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Find all bookings for a car with owner verification.
     * Returns bookings only if the authenticated user is the car owner.
     * Prevents horizontal privilege escalation (Owner A viewing Owner B's bookings).
     * 
     * @param carId Car ID
     * @param ownerId Authenticated owner's user ID
     * @return List of bookings for the car (empty if user is not the owner)
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "WHERE c.id = :carId " +
           "AND c.owner.id = :ownerId " +
           "ORDER BY b.startTime DESC")
    List<Booking> findByCarIdForOwner(@Param("carId") Long carId, @Param("ownerId") Long ownerId);

    /**
     * Find bookings for multiple cars with owner verification.
     * Ensures all returned bookings belong to cars owned by the authenticated user.
     * 
     * @param carIds List of car IDs
     * @param ownerId Authenticated owner's user ID
     * @return List of bookings for cars owned by the user
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "WHERE c.id IN :carIds " +
           "AND c.owner.id = :ownerId")
    List<Booking> findByCarIdInForOwner(@Param("carIds") List<Long> carIds, @Param("ownerId") Long ownerId);

    /**
     * Find booking by ID for conversation view with eager loading.
     * Optimized for BookingConversationDTO construction:
     * - Eagerly loads Car (brand, model, year, images)
     * - Eagerly loads Renter (for RLS check)
     * - Eagerly loads Owner (for RLS check)
     * 
     * Note: Car.images (OneToMany to CarImage) loaded via LEFT JOIN FETCH for image URLs.
     * 
     * @param id Booking ID
     * @return Optional containing booking with all conversation-view dependencies
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner o " +
           "LEFT JOIN FETCH c.images " +
           "WHERE b.id = :id")
    java.util.Optional<Booking> findByIdForConversationView(@Param("id") Long id);

    // ========== HOST APPROVAL WORKFLOW QUERIES ==========

    /**
     * Find all pending approval requests for an owner's cars.
     * Returns bookings with status PENDING_APPROVAL for all cars owned by the user.
     * Eagerly loads car, renter, and owner to prevent lazy-loading issues.
     * 
     * @param ownerId Authenticated owner's user ID
     * @return List of pending approval requests sorted by creation date (newest first)
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "JOIN FETCH c.owner o " +
           "WHERE b.status = 'PENDING_APPROVAL' " +
           "AND c.owner.id = :ownerId " +
           "ORDER BY b.id DESC")
    List<Booking> findPendingBookingsForOwner(@Param("ownerId") Long ownerId);

    /**
     * Find all pending bookings created before a specific threshold.
     * Used by scheduler to auto-expire requests that haven't been approved/declined in time.
     * 
     * @param threshold Timestamp before which pending bookings are considered expired
     * @return List of expired pending bookings
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "WHERE b.status = 'PENDING_APPROVAL' " +
           "AND b.decisionDeadlineAt < :threshold")
    List<Booking> findPendingBookingsBefore(@Param("threshold") LocalDateTime threshold);

    /**
     * Check for conflicting bookings during host approval.
     * Used for availability checks during approval to prevent race conditions.
     * 
     * Only in-progress bookings block times. PENDING_APPROVAL from other users
     * don't block because only one can be approved.
     * 
     * Blocking statuses (already approved/in-progress):
     * - ACTIVE: Confirmed, waiting for check-in
     * - CHECK_IN_OPEN, CHECK_IN_HOST_COMPLETE, CHECK_IN_COMPLETE: Check-in in progress
     * - IN_TRIP: Trip is happening
     * 
     * COMPLETED/NO_SHOW bookings do NOT block - those times are now free.
     * 
     * @param carId Car ID
     * @param startTime Booking start timestamp
     * @param endTime Booking end timestamp
     * @return true if there are conflicting in-progress bookings
     */
    @Query("SELECT COUNT(b) > 0 FROM Booking b WHERE b.car.id = :carId " +
           "AND b.status IN ('ACTIVE', 'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP') " +
           "AND b.startTime < :endTime AND b.endTime > :startTime")
    boolean existsConflictingBookings(
            @Param("carId") Long carId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * Find bookings for public calendar view.
     * Returns blocking bookings to show unavailable times.
     * 
     * Blocking Statuses (shown as unavailable):
     * - PENDING_APPROVAL: Awaiting host decision (blocks to prevent overbooking)
     * - ACTIVE: Confirmed, waiting for check-in window
     * - CHECK_IN_OPEN: Check-in window is open
     * - CHECK_IN_HOST_COMPLETE: Host completed check-in
     * - CHECK_IN_COMPLETE: Both parties completed check-in
     * - IN_TRIP: Trip is in progress
     * 
     * Non-Blocking Statuses (EXCLUDED - times are free):
     * - CANCELLED: Trip was cancelled
     * - DECLINED: Host rejected
     * - COMPLETED: Trip finished (times now free for future bookings)
     * - EXPIRED, EXPIRED_SYSTEM: Request expired
     * - NO_SHOW_HOST, NO_SHOW_GUEST: No-show (times freed)
     * 
     * @param carId Car ID
     * @return List of blocking bookings for calendar display
     */
    @Query("SELECT b FROM Booking b " +
           "WHERE b.car.id = :carId " +
           "AND b.status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP') " +
           "ORDER BY b.startTime ASC")
    List<Booking> findPublicBookingsForCar(@Param("carId") Long carId);

    // ========== RENTER AVAILABILITY LOGIC (Single-Booking Constraint) ==========

    /**
     * Check if a user has any overlapping bookings for the given time range.
     * 
     * Business Rule: "One Driver, One Car"
     * A renter cannot physically drive two cars simultaneously. This method
     * enforces the constraint at the application layer (soft guardrail).
     * 
     * Overlap Formula (Interval Intersection):
     * Two intervals [A_start, A_end) and [B_start, B_end) overlap if:
     * (A_start < B_end) AND (A_end > B_start)
     * 
     * Status Filter (blocking statuses only):
     * - PENDING_APPROVAL: Request awaiting host decision (blocks user's times)
     * - ACTIVE: Confirmed, waiting for check-in window
     * - CHECK_IN_OPEN, CHECK_IN_HOST_COMPLETE, CHECK_IN_COMPLETE: Check-in in progress
     * - IN_TRIP: Trip is in progress
     * 
     * Non-blocking statuses:
     * - CANCELLED, DECLINED, COMPLETED, EXPIRED, EXPIRED_SYSTEM, NO_SHOW_HOST, NO_SHOW_GUEST
     * 
     * Security:
     * - Uses user ID (not email) for precise matching
     * - Query is parameterized (SQL injection safe)
     * 
     * Performance:
     * - Uses idx_booking_renter_time_overlap index
     * - Returns boolean (COUNT > 0), not full entities
     * 
     * @param userId Renter's user ID
     * @param startTime Requested booking start timestamp
     * @param endTime Requested booking end timestamp
     * @return true if user has overlapping booking (should reject new booking)
     */
    @Query("SELECT COUNT(b) > 0 FROM Booking b " +
           "WHERE b.renter.id = :userId " +
           "AND b.status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP') " +
           "AND b.startTime < :endTime " +
           "AND b.endTime > :startTime")
    boolean existsOverlappingUserBooking(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * Find all overlapping user bookings for detailed error messaging.
     * Used when existsOverlappingUserBooking returns true to provide
     * context about which booking is conflicting.
     * 
     * @param userId Renter's user ID
     * @param startTime Requested booking start timestamp
     * @param endTime Requested booking end timestamp
     * @return List of conflicting bookings (for logging/debugging)
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "WHERE b.renter.id = :userId " +
           "AND b.status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP') " +
           "AND b.startTime < :endTime " +
           "AND b.endTime > :startTime")
    List<Booking> findOverlappingUserBookings(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    // ========== CHECK-IN WORKFLOW QUERIES ==========

    /**
     * Find bookings eligible for check-in window opening.
     * ACTIVE bookings starting within the time range that don't have a session yet.
     * 
     * Used by CheckInScheduler to open check-in windows T-24h before trip start.
     * 
     * @param startFrom Start time range (inclusive)
     * @param startTo End time range (inclusive)
     * @return Bookings ready for check-in window opening
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner " +
           "WHERE b.status = 'ACTIVE' " +
           "AND b.checkInSessionId IS NULL " +
           "AND b.startTime >= :startFrom " +
           "AND b.startTime <= :startTo")
    List<Booking> findBookingsForCheckInWindowOpening(
            @Param("startFrom") LocalDateTime startFrom,
            @Param("startTo") LocalDateTime startTo
    );

    /**
     * Find bookings needing check-in reminder (opened but not completed by host).
     * 
     * @param status Current booking status
     * @param openedBefore Bookings opened before this timestamp
     * @return Bookings needing reminder
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner " +
           "WHERE b.status = :status " +
           "AND b.checkInOpenedAt IS NOT NULL " +
           "AND b.checkInOpenedAt < :openedBefore")
    List<Booking> findBookingsNeedingReminder(
            @Param("status") BookingStatus status,
            @Param("openedBefore") java.time.Instant openedBefore
    );

    /**
     * Find potential host no-shows.
     * CHECK_IN_OPEN bookings where trip start + grace period has passed.
     * 
     * @param status CHECK_IN_OPEN
     * @param thresholdTime Timestamp threshold for no-show detection
     * @return Potential host no-show bookings
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner " +
           "WHERE b.status = :status " +
           "AND b.hostCheckInCompletedAt IS NULL " +
           "AND b.startTime < :thresholdTime")
    List<Booking> findPotentialHostNoShows(
            @Param("status") BookingStatus status,
            @Param("thresholdTime") LocalDateTime thresholdTime
    );

    /**
     * Find potential guest no-shows.
     * CHECK_IN_HOST_COMPLETE bookings where host completed + grace period has passed.
     * 
     * @param status CHECK_IN_HOST_COMPLETE
     * @param hostCompletedBefore Threshold for host completion time
     * @return Potential guest no-show bookings
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner " +
           "WHERE b.status = :status " +
           "AND b.guestCheckInCompletedAt IS NULL " +
           "AND b.hostCheckInCompletedAt IS NOT NULL " +
           "AND b.hostCheckInCompletedAt < :hostCompletedBefore")
    List<Booking> findPotentialGuestNoShows(
            @Param("status") BookingStatus status,
            @Param("hostCompletedBefore") java.time.Instant hostCompletedBefore
    );

    /**
     * Find bookings currently in check-in phase.
     * Used for dashboard/monitoring.
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "WHERE b.status IN ('CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE') " +
           "ORDER BY b.startTime ASC")
    List<Booking> findBookingsInCheckInPhase();

    /**
     * Find active trips (IN_TRIP status).
     * Used for monitoring and scheduled checkout processing.
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "WHERE b.status = 'IN_TRIP' " +
           "ORDER BY b.endTime ASC")
    List<Booking> findActiveTrips();

    /**
     * Find booking by check-in session ID.
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner " +
           "WHERE b.checkInSessionId = :sessionId")
    java.util.Optional<Booking> findByCheckInSessionId(@Param("sessionId") String sessionId);

    // ========== CHECKOUT WORKFLOW QUERIES ==========

    /**
     * Find bookings eligible for checkout window opening.
     * IN_TRIP bookings where end time is approaching (within threshold).
     * 
     * @param thresholdTime Checkout window opens when endTime is before this
     * @return Bookings ready for checkout window opening
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner " +
           "WHERE b.status = 'IN_TRIP' " +
           "AND b.checkoutSessionId IS NULL " +
           "AND b.endTime <= :thresholdTime")
    List<Booking> findBookingsForCheckoutWindowOpening(
            @Param("thresholdTime") LocalDateTime thresholdTime
    );

    /**
     * Find overdue checkouts (late returns).
     * IN_TRIP or CHECKOUT_OPEN bookings where end time + grace period has passed.
     * 
     * @param thresholdTime Time after which return is considered late
     * @return Late return bookings
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner " +
           "WHERE b.status IN ('IN_TRIP', 'CHECKOUT_OPEN', 'CHECKOUT_GUEST_COMPLETE') " +
           "AND b.endTime < :thresholdTime")
    List<Booking> findOverdueCheckouts(
            @Param("thresholdTime") LocalDateTime thresholdTime
    );

    // ========== ADMIN MANAGEMENT QUERIES ==========

    /**
     * Find all bookings for a renter by user ID.
     * Used by admin for user profile view and cascade delete.
     * 
     * @param renterId Renter's user ID
     * @return List of all bookings for the renter
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "WHERE b.renter.id = :renterId " +
           "ORDER BY b.startTime DESC")
    List<Booking> findByRenterId(@Param("renterId") Long renterId);

    /**
     * Find bookings by renter ID and specific statuses.
     * Used for cascade operations (e.g., cancel active bookings on user delete).
     * 
     * @param renterId Renter's user ID
     * @param statuses List of statuses to filter by
     * @return Matching bookings
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "WHERE b.renter.id = :renterId " +
           "AND b.status IN :statuses")
    List<Booking> findByRenterIdAndStatusIn(
            @Param("renterId") Long renterId,
            @Param("statuses") List<BookingStatus> statuses
    );

    /**
     * Count total bookings for a renter.
     * Used for admin user profile statistics.
     * 
     * @param renterId Renter's user ID
     * @return Total booking count
     */
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.renter.id = :renterId")
    Integer countByRenterId(@Param("renterId") Long renterId);

    /**
     * Count active trips (IN_TRIP status) for admin dashboard.
     * 
     * @return Number of currently active trips
     */
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.status = 'IN_TRIP'")
    Long countActiveTrips();

    /**
     * Count bookings by status for admin dashboard.
     * 
     * @param status Booking status
     * @return Count of bookings with that status
     */
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.status = :status")
    Long countByStatus(@Param("status") BookingStatus status);
    
    /**
     * Find bookings by status and updated before a certain date.
     * Used for payout queue (completed bookings past holding period).
     * 
     * @param status Booking status
     * @param cutoffDate Only bookings updated before this date
     * @param pageable Pagination
     * @return Page of bookings
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH c.owner " +
           "JOIN FETCH b.renter " +
           "WHERE b.status = :status AND b.updatedAt < :cutoffDate " +
           "ORDER BY b.updatedAt ASC")
    org.springframework.data.domain.Page<Booking> findByStatusAndUpdatedAtBefore(
        @Param("status") BookingStatus status,
        @Param("cutoffDate") java.time.Instant cutoffDate,
        org.springframework.data.domain.Pageable pageable
    );
    
    /**
     * Find bookings by status and updated before date (non-paginated).
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH c.owner " +
           "WHERE b.status = :status AND b.updatedAt < :cutoffDate")
    List<Booking> findByStatusAndUpdatedAtBefore(
        @Param("status") BookingStatus status,
        @Param("cutoffDate") java.time.Instant cutoffDate
    );
    
    /**
     * Find bookings with multiple statuses (for escrow calculations).
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "WHERE b.status IN :statuses")
    List<Booking> findByStatusIn(@Param("statuses") List<BookingStatus> statuses);
    
    /**
     * Find bookings by status and updated between dates (for analytics).
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "WHERE b.status = :status AND b.updatedAt BETWEEN :start AND :end")
    List<Booking> findByStatusAndUpdatedAtBetween(
        @Param("status") BookingStatus status,
        @Param("start") java.time.Instant start,
        @Param("end") java.time.Instant end
    );
    
    /**
     * Find bookings created between dates (for cohort analysis).
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "WHERE b.createdAt BETWEEN :start AND :end")
    List<Booking> findByCreatedAtBetween(
        @Param("start") java.time.Instant start,
        @Param("end") java.time.Instant end
    );
    
    /**
     * Find bookings by status and approvedAt between dates (for analytics).
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "WHERE b.status = :status AND b.approvedAt BETWEEN :start AND :end")
    List<Booking> findByStatusAndApprovedAtBetween(
        @Param("status") BookingStatus status,
        @Param("start") java.time.LocalDateTime start,
        @Param("end") java.time.LocalDateTime end
    );
    
    // ========== VAL-004 PHASE 6: CHECK-IN DISPUTE TIMEOUT QUERIES ==========
    
    /**
     * Find bookings in CHECK_IN_DISPUTE status that haven't been updated in the threshold period.
     * Used by scheduler to detect stale disputes needing escalation or auto-cancellation.
     * 
     * @param status BookingStatus.CHECK_IN_DISPUTE
     * @param threshold Instant representing 24 hours ago
     * @return List of bookings with stale disputes, ordered by trip start (most urgent first)
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH c.owner " +
           "JOIN FETCH b.renter r " +
           "WHERE b.status = :status " +
           "AND b.updatedAt < :threshold " +
           "ORDER BY b.startTime ASC")
    List<Booking> findStaleCheckInDisputes(
        @Param("status") BookingStatus status,
        @Param("threshold") java.time.Instant threshold
    );
}


