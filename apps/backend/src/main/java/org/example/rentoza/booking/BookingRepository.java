package org.example.rentoza.booking;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.example.rentoza.car.Car;
import org.example.rentoza.payment.ChargeLifecycleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long>, JpaSpecificationExecutor<Booking> {

    /**
     * Find an existing booking by idempotency key.
     * Used to detect duplicate creation requests on client retry.
     */
    java.util.Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    /**
     * Idempotency replay query — same as {@link #findByIdempotencyKey} but with all
     * associations eagerly fetched. Prevents {@code LazyInitializationException} when
     * the returned entity is used to build {@code BookingResponseDTO} after the original
     * {@code @Transactional} session has closed.
     *
     * <p>Used exclusively in the idempotency early-return path of
     * {@code BookingService.createBooking} and the race-collision catch block.
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner " +
           "WHERE b.idempotencyKey = :key")
    java.util.Optional<Booking> findByIdempotencyKeyWithRelations(@Param("key") String key);

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
     * Same as findByIdWithRelations but acquires a PESSIMISTIC_WRITE lock.
     * Used for approval/decline decisions to avoid race conditions with expiry jobs.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner o " +
           "WHERE b.id = :id")
    java.util.Optional<Booking> findByIdWithRelationsForUpdate(@Param("id") Long id);

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
    @QueryHints({
        @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000") // R4: 5s timeout prevents thread starvation
    })
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
           "WHERE b.status = 'COMPLETED' AND b.updatedAt >= :start AND b.updatedAt < :end")
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
           "AND b.status IN :statuses " +
           "AND b.startTime < :endTime AND b.endTime > :startTime")
    boolean existsOverlappingBookings(
            @Param("carId") Long carId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("statuses") Collection<BookingStatus> statuses
    );

    /**
     * Convenience overload using the canonical blocking status set.
     */
    default boolean existsOverlappingBookings(Long carId, LocalDateTime startTime, LocalDateTime endTime) {
        return existsOverlappingBookings(carId, startTime, endTime, BookingStatus.BLOCKING_STATUSES);
    }

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
     * IMPORTANT: Returns actual entities (not boolean count) so that JPA properly
     * acquires row-level locks via SELECT ... FOR UPDATE. A COUNT query may not
     * lock rows on all databases (e.g., PostgreSQL aggregate functions).
     * 
     * Usage Pattern:
     * 1. Call acquireCarAdvisoryLock() FIRST to serialize access
     * 2. Call this method BEFORE creating a new Booking entity
     * 3. If returns non-empty list, throw BookingConflictException
     * 4. If returns empty list, safe to proceed with booking creation
     * 5. Lock is released when transaction commits/rolls back
     * 
     * @param carId Car ID to check
     * @param startTime Booking start timestamp
     * @param endTime Booking end timestamp
     * @return list of conflicting bookings (empty if no conflicts)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
        @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000") // 5 second timeout
    })
    @Query("SELECT b FROM Booking b WHERE b.car.id = :carId " +
           "AND b.status IN :statuses " +
           "AND b.startTime < :endTime AND b.endTime > :startTime")
    List<Booking> findOverlappingBookingsWithLock(
            @Param("carId") Long carId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("statuses") Collection<BookingStatus> statuses
    );

    /**
     * Convenience overload using the canonical blocking status set.
     */
    default List<Booking> findOverlappingBookingsWithLock(Long carId, LocalDateTime startTime, LocalDateTime endTime) {
        return findOverlappingBookingsWithLock(carId, startTime, endTime, BookingStatus.BLOCKING_STATUSES);
    }

    /**
     * Acquire a PostgreSQL advisory lock for a car.
     * 
     * <p><b>P0 Fix:</b> FOR UPDATE only locks existing rows. When the slot is empty
     * (no overlapping bookings yet), no rows are locked and two concurrent transactions
     * can both proceed. This advisory lock serializes ALL booking attempts for a given car,
     * preventing the empty-slot race condition.
     * 
     * <p>Uses pg_advisory_xact_lock which auto-releases when the transaction ends.
     * The lock key is the car_id itself (unique per car).
     * 
     * @param carId Car ID to acquire lock for
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(CAST(:carId AS BIGINT))) AS lock_acquired", nativeQuery = true)
    Integer acquireCarAdvisoryLock(@Param("carId") Long carId);

    /**
     * Convenience method: Check if overlapping bookings exist with pessimistic locking.
     * Delegates to {@link #findOverlappingBookingsWithLock} and checks if result is non-empty.
     */
    default boolean existsOverlappingBookingsWithLock(Long carId, LocalDateTime startTime, LocalDateTime endTime) {
        acquireCarAdvisoryLock(carId);
        return !findOverlappingBookingsWithLock(carId, startTime, endTime).isEmpty();
    }

    /**
     * Find all bookings that are overdue (end time in the past) but not yet marked as COMPLETED.
     * Used by scheduled task to auto-complete bookings.
     *
     * F-AC-1 FIX: Targets ONLY ACTIVE bookings (pre-check-in orphans where trip time
     * fully elapsed without check-in opening). IN_TRIP bookings are deliberately EXCLUDED
     * because they must go through the checkout saga (CheckOutScheduler -> CheckoutSagaOrchestrator)
     * to ensure deposit settlement, damage assessment, and late fee calculation.
     * Without this exclusion, the hourly auto-complete would race ahead of the 6-hourly
     * ghost trip handler, marking IN_TRIP bookings as COMPLETED before deposit capture.
     *
     * <p><b>WI-14 Dispute safety (by design):</b> The ACTIVE-only filter inherently protects
     * disputed bookings from accidental auto-completion:
     * <ul>
     *   <li>{@code CHECK_IN_DISPUTE} -- booking is in dispute resolution, not ACTIVE</li>
     *   <li>{@code CHECKOUT_DAMAGE_DISPUTE} -- booking is in damage claim flow, not ACTIVE</li>
     *   <li>{@code IN_TRIP} -- must go through checkout saga, not auto-completed here</li>
     * </ul>
     * This is by design, not accidental -- the state machine ensures only pre-check-in
     * orphaned bookings are eligible for the simple auto-complete path.
     */
    @Query("SELECT b FROM Booking b " +
           "WHERE b.status = :status " +
           "AND b.endTime < :currentTime")
    List<Booking> findOverdueBookings(
            @Param("status") BookingStatus status,
            @Param("currentTime") LocalDateTime currentTime
    );

    /**
     * Convenience overload targeting ACTIVE bookings only (canonical usage).
     */
    default List<Booking> findOverdueBookings(LocalDateTime currentTime) {
        return findOverdueBookings(BookingStatus.ACTIVE, currentTime);
    }

    /**
     * Paginated version of findOverdueBookings for batch processing.
     * Prevents loading all overdue bookings at once on a growing platform.
     *
     * @param status Booking status to filter (typically ACTIVE)
     * @param currentTime Current time for overdue comparison
     * @param pageable Page request (e.g., PageRequest.of(0, 50))
     * @return Page of overdue bookings
     */
    @Query("SELECT b FROM Booking b WHERE b.status = :status AND b.endTime < :currentTime")
    Page<Booking> findOverdueBookingsPaged(@Param("status") BookingStatus status, @Param("currentTime") LocalDateTime currentTime, Pageable pageable);

    /**
     * Paginated query for user bookings with full details.
     * Used by the /api/bookings/me/paged endpoint for scalable pagination.
     *
     * @param userId Renter's user ID
     * @param pageable Page request
     * @return Page of bookings with car and renter details eagerly loaded
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner " +
           "WHERE r.id = :userId " +
           "ORDER BY b.startTime DESC")
    Page<Booking> findByRenterIdWithDetailsPaged(@Param("userId") Long userId, Pageable pageable);

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
           "AND b.status IN :statuses " +
           "AND b.startTime < :endTime AND b.endTime > :startTime")
    List<Booking> findByCarIdAndTimeRangeBlocking(
            @Param("carId") Long carId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("statuses") Collection<BookingStatus> statuses
    );

    /**
     * Convenience overload using the canonical blocking status set.
     */
    default List<Booking> findByCarIdAndTimeRangeBlocking(Long carId, LocalDateTime startTime, LocalDateTime endTime) {
        return findByCarIdAndTimeRangeBlocking(carId, startTime, endTime, BookingStatus.BLOCKING_STATUSES);
    }

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
     * Find pending bookings with future decision deadlines.
     * Used by reminder scheduler.
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH c.owner o " +
           "JOIN FETCH b.renter r " +
           "WHERE b.status = 'PENDING_APPROVAL' " +
           "AND b.decisionDeadlineAt IS NOT NULL " +
           "AND b.decisionDeadlineAt > :threshold")
    List<Booking> findPendingBookingsAfter(@Param("threshold") LocalDateTime threshold);

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
           "AND b.status IN :statuses " +
           "AND b.startTime < :endTime " +
           "AND b.endTime > :startTime")
    boolean existsOverlappingUserBooking(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("statuses") Collection<BookingStatus> statuses
    );

    /**
     * Convenience overload using the canonical blocking status set.
     */
    default boolean existsOverlappingUserBooking(Long userId, LocalDateTime startTime, LocalDateTime endTime) {
        return existsOverlappingUserBooking(userId, startTime, endTime, BookingStatus.BLOCKING_STATUSES);
    }

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
           "AND b.status IN :statuses " +
           "AND b.startTime < :endTime " +
           "AND b.endTime > :startTime")
    List<Booking> findOverlappingUserBookings(
            @Param("userId") Long userId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("statuses") Collection<BookingStatus> statuses
    );

    /**
     * Convenience overload using the canonical blocking status set.
     */
    default List<Booking> findOverlappingUserBookings(Long userId, LocalDateTime startTime, LocalDateTime endTime) {
        return findOverlappingUserBookings(userId, startTime, endTime, BookingStatus.BLOCKING_STATUSES);
    }

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
          "AND b.startTime < :tripStartedBefore " +
           "AND b.hostCheckInCompletedAt < :hostCompletedBefore")
    List<Booking> findPotentialGuestNoShows(
            @Param("status") BookingStatus status,
           @Param("tripStartedBefore") LocalDateTime tripStartedBefore,
            @Param("hostCompletedBefore") java.time.Instant hostCompletedBefore
    );

    /**
     * Find stale handshake sessions.
     * CHECK_IN_COMPLETE bookings where handshake was not completed within timeout after trip start.
     */
    @Query("SELECT b FROM Booking b " +
          "JOIN FETCH b.car c " +
          "JOIN FETCH b.renter r " +
          "LEFT JOIN FETCH c.owner " +
          "WHERE b.status = :status " +
          "AND b.handshakeCompletedAt IS NULL " +
           "AND b.guestCheckInCompletedAt IS NOT NULL " +
          "AND b.startTime < :startedBefore")
    List<Booking> findStaleCheckInHandshakes(
           @Param("status") BookingStatus status,
           @Param("startedBefore") LocalDateTime startedBefore
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
     * M-2 FIX: Fetch recent bookings with all relations eagerly loaded (N+1 prevention).
     * Used for admin dashboard recent bookings widget.
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter " +
           "LEFT JOIN FETCH c.owner " +
           "ORDER BY b.createdAt DESC")
    List<Booking> findRecentBookingsWithRelations(Pageable pageable);

    /**
     * C-3 FIX: Find booking by ID with PESSIMISTIC_WRITE lock for payout processing.
     * Prevents double-payout when two concurrent batch requests include the same booking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
        @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")
    })
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "LEFT JOIN FETCH c.owner " +
           "WHERE b.id = :id")
    java.util.Optional<Booking> findByIdWithLockForPayout(@Param("id") Long id);

    /**
     * H-2 FIX: Aggregate sum of totalAmount by booking statuses.
     * Used for escrow balance and frozen funds calculations.
     */
    @Query("SELECT COALESCE(SUM(b.totalAmount), 0) FROM Booking b " +
           "WHERE b.status IN :statuses")
    java.math.BigDecimal sumTotalAmountByStatuses(@Param("statuses") java.util.Collection<BookingStatus> statuses);

    /**
     * H-9 FIX: Database-level payout queue query with paymentReference IS NULL filter.
     * Returns correct pagination totals.
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "LEFT JOIN FETCH c.owner " +
           "WHERE b.status = :status " +
           "AND b.updatedAt < :cutoff " +
           "AND b.paymentReference IS NULL " +
           "ORDER BY b.updatedAt ASC")
    Page<Booking> findPendingPayouts(
        @Param("status") BookingStatus status,
        @Param("cutoff") Instant cutoff,
        Pageable pageable);

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
     * Count bookings with a given status created within a period.
     */
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.status = :status AND b.createdAt >= :start AND b.createdAt < :end")
    Long countByStatusAndCreatedAtBetween(
        @Param("status") BookingStatus status,
        @Param("start") java.time.LocalDateTime start,
        @Param("end") java.time.LocalDateTime end);
    
    /**
     * Count bookings created within a period.
     */
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.createdAt >= :start AND b.createdAt < :end")
    Long countBookingsInPeriod(
        @Param("start") java.time.LocalDateTime start,
        @Param("end") java.time.LocalDateTime end);
    
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
           "WHERE b.createdAt >= :start AND b.createdAt < :end")
    List<Booking> findByCreatedAtBetween(
        @Param("start") java.time.LocalDateTime start,
        @Param("end") java.time.LocalDateTime end
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
    
    // ========== VAL-010: CHECKOUT DAMAGE DISPUTE TIMEOUT QUERIES ==========
    
    /**
     * Find bookings in CHECKOUT_DAMAGE_DISPUTE status where the deposit hold deadline has passed.
     * Used by scheduler to detect disputes that need escalation due to guest non-response.
     * 
     * @param status BookingStatus.CHECKOUT_DAMAGE_DISPUTE
     * @param deadline Current time (disputes with holdUntil before this are expired)
     * @return List of bookings with expired damage disputes
     * @since VAL-010
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH c.owner " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH b.checkoutDamageClaim " +
           "WHERE b.status = :status " +
           "AND b.securityDepositHoldUntil < :deadline " +
           "ORDER BY b.securityDepositHoldUntil ASC")
    List<Booking> findByStatusAndSecurityDepositHoldUntilBefore(
        @Param("status") BookingStatus status,
        @Param("deadline") java.time.Instant deadline
    );

    // ========== P0-5 FIX: OPTIMIZED QUERIES TO REPLACE findAll() ==========

    // ========== PAYMENT LIFECYCLE SCHEDULER QUERIES ==========
    
    /**
     * Find bookings needing payment capture (T-24h before trip start).
     * 
     * <p>Targets ACTIVE/APPROVED bookings with AUTHORIZED payment status
     * where the trip starts within the next 24 hours.
     * 
     * @param statuses Booking statuses to check (ACTIVE, APPROVED)
     * @param captureWindow Time threshold (now + 24h)
     * @return Bookings ready for payment capture
     */
    /**
     * Find IN_TRIP bookings whose payment is still AUTHORIZED but not yet CAPTURED.
     *
     * <p><b>P0-1 fix:</b> The T-24h pre-capture scheduler has been removed. Capture now
     * happens exclusively at the physical hand-off handshake
     * ({@code CheckInService.confirmHandshake} → {@code BookingPaymentService.captureBookingPaymentNow}).
     * This scheduler job is retained ONLY as a safety-net fallback for bookings that
     * transitioned to {@code IN_TRIP} but whose handshake capture never completed
     * (e.g., transient provider timeout). Requiring {@code b.tripStartedAt IS NOT NULL}
     * guarantees the physical hand-off has occurred before we attempt to charge the renter.
     *
     * @return IN_TRIP bookings still awaiting capture
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH c.owner " +
           "JOIN FETCH b.renter r " +
           "WHERE b.status = 'IN_TRIP' " +
           "AND b.chargeLifecycleStatus IN ('AUTHORIZED', 'CAPTURE_FAILED') " +
           "AND b.tripStartedAt IS NOT NULL " +
           "AND b.bookingAuthorizationId IS NOT NULL " +
           "AND b.captureAttempts < 3 " +
           "ORDER BY b.tripStartedAt ASC")
    List<Booking> findBookingsNeedingPaymentCapture();
    
    /**
     * Find COMPLETED bookings eligible for deposit release (T+48h after trip end).
     * 
     * <p>Targets COMPLETED bookings where:
     * <ul>
     *   <li>Deposit is still held (not released/captured)</li>
     *   <li>Trip ended more than 48 hours ago</li>
     *   <li>No pending damage claims blocking release</li>
     * </ul>
     * 
     * @param status BookingStatus.COMPLETED
     * @param releaseAfter Instant representing 48 hours ago
     * @return Bookings eligible for deposit auto-release
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH c.owner " +
           "JOIN FETCH b.renter r " +
           "WHERE b.status = :status " +
           "AND b.depositAuthorizationId IS NOT NULL " +
           "AND (b.securityDepositReleased IS NULL OR b.securityDepositReleased = false) " +
           "AND b.tripEndedAt IS NOT NULL " +
           "AND b.tripEndedAt < :releaseAfter " +
           "ORDER BY b.tripEndedAt ASC")
    List<Booking> findBookingsEligibleForDepositRelease(
        @Param("status") BookingStatus status,
        @Param("releaseAfter") java.time.Instant releaseAfter
    );
    
    /**
     * Find bookings with deposits held past the auto-release deadline (7 days max).
     * Safety net: deposits should never be held indefinitely.
     * 
     * @param maxHoldDeadline Instant representing 7 days ago
     * @return Bookings with overdue deposit holds
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH c.owner " +
           "JOIN FETCH b.renter r " +
           "WHERE b.depositAuthorizationId IS NOT NULL " +
           "AND (b.securityDepositReleased IS NULL OR b.securityDepositReleased = false) " +
           "AND b.tripEndedAt IS NOT NULL " +
           "AND b.tripEndedAt < :maxHoldDeadline " +
           "AND b.status IN :terminalStatuses " +
           "ORDER BY b.tripEndedAt ASC")
    List<Booking> findBookingsWithOverdueDepositHold(
        @Param("maxHoldDeadline") java.time.Instant maxHoldDeadline,
        @Param("terminalStatuses") List<BookingStatus> terminalStatuses
    );

    /**
     * Find bookings needing checkout window opened.
     * 
     * P0-5 FIX: Replaces loading ALL bookings and filtering in Java.
     * Uses indexed query on status + endTime columns.
     * 
     * @param status Should be BookingStatus.IN_TRIP
     * @param endTimeBefore Bookings ending before this time need checkout
     * @return Bookings ready for checkout window opening
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH c.owner " +
           "JOIN FETCH b.renter r " +
           "WHERE b.status = :status " +
           "AND b.checkoutSessionId IS NULL " +
           "AND b.endTime <= :endTimeBefore " +
           "ORDER BY b.endTime ASC")
    List<Booking> findBookingsNeedingCheckoutOpening(
        @Param("status") BookingStatus status,
        @Param("endTimeBefore") LocalDateTime endTimeBefore
    );

    /**
     * Get all booking IDs (admin only).
     * 
     * P0-5 FIX: Replaces loading full Booking entities just to get IDs.
     * Returns only IDs - O(n) data transfer reduced by 99%.
     * 
     * @return List of all booking IDs
     */
    @Query("SELECT b.id FROM Booking b ORDER BY b.id DESC")
    List<Long> findAllBookingIds();

    /**
     * Find completed bookings that were completed recently (within last N days).
     * Used by payout scheduler to batch-schedule PayoutLedger entries.
     * The PayoutLedger service handles idempotency so duplicate calls are safe.
     *
     * @param since Only look at bookings completed after this instant
     * @return Completed bookings eligible for payout scheduling
     */
    @Query("""
           SELECT b FROM Booking b
           JOIN FETCH b.car c
           JOIN FETCH c.owner
           JOIN FETCH b.renter r
           WHERE b.status = 'COMPLETED'
             AND b.tripEndedAt IS NOT NULL
             AND b.tripEndedAt >= :since
           ORDER BY b.tripEndedAt DESC
           """)
    List<Booking> findRecentlyCompletedBookings(@Param("since") java.time.Instant since);

    /**
     * All COMPLETED bookings regardless of date — used for payout scheduling backfill.
     * Prefer {@link #findRecentlyCompletedBookings} for incremental processing.
     */
    @Query("""
           SELECT b FROM Booking b
           JOIN FETCH b.car c
           JOIN FETCH c.owner
           JOIN FETCH b.renter r
           WHERE b.status = 'COMPLETED'
             AND b.tripEndedAt IS NOT NULL
           ORDER BY b.tripEndedAt DESC
           """)
    List<Booking> findCompletedBookingsNeedingPayout();

    /**
     * Count total bookings (for admin dashboard).
     * 
     * P0-5 FIX: Use COUNT instead of loading entities.
     * 
     * @return Total booking count
     */
    @Query("SELECT COUNT(b) FROM Booking b")
    long countAll();

    // ========== P0-6: AUTH EXPIRY QUERY ==========

    /**
     * Find ACTIVE or APPROVED bookings in AUTHORIZED charge-lifecycle state whose payment
     * authorisation expires before {@code thresholdTime}. Used by the reauth scheduler job
     * to flag bookings that need re-authorisation before auth lapses.
     *
     * <p><b>P1-1 fix:</b> Only ACTIVE/APPROVED bookings are relevant — bookings in terminal
     * or pre-approval states should not be marked REAUTH_REQUIRED.
     *
     * @param thresholdTime Instant boundary (e.g. now + 48h)
     * @return Bookings at risk of auth expiry
     */
    @Query("""
           SELECT b FROM Booking b
           JOIN FETCH b.car c
           JOIN FETCH c.owner
           JOIN FETCH b.renter r
           WHERE b.chargeLifecycleStatus = :status
             AND b.status IN ('ACTIVE', 'APPROVED')
             AND b.bookingAuthExpiresAt IS NOT NULL
             AND b.bookingAuthExpiresAt <= :thresholdTime
           ORDER BY b.bookingAuthExpiresAt ASC
           """)
    List<Booking> findBookingsWithExpiringAuth(
        @Param("thresholdTime") Instant thresholdTime,
        @Param("status") ChargeLifecycleStatus status
    );

    /**
     * H-11: Find bookings with deposit authorization expiring before the given threshold.
     * Only returns bookings where the deposit is AUTHORIZED (not yet released/captured)
     * and where the trip is in-progress or pending.
     */
    @Query("""
           SELECT b FROM Booking b
           JOIN FETCH b.car c
           JOIN FETCH c.owner
           JOIN FETCH b.renter r
           WHERE b.depositLifecycleStatus = org.example.rentoza.payment.DepositLifecycleStatus.AUTHORIZED
             AND b.status IN ('IN_TRIP', 'CHECK_IN_COMPLETE', 'ACTIVE', 'APPROVED')
             AND b.depositAuthExpiresAt IS NOT NULL
             AND b.depositAuthExpiresAt <= :thresholdTime
           ORDER BY b.depositAuthExpiresAt ASC
           """)
    List<Booking> findBookingsWithExpiringDepositAuth(@Param("thresholdTime") Instant thresholdTime);

    // ========== BATCH AVAILABILITY QUERIES (P2 N+1 fix) ==========

    /**
     * Batch: find car IDs that have at least one overlapping booking in blocking status.
     *
     * Blocking statuses (same as {@link #existsOverlappingBookings}):
     * PENDING_APPROVAL, ACTIVE, CHECK_IN_OPEN, CHECK_IN_HOST_COMPLETE,
     * CHECK_IN_COMPLETE, IN_TRIP
     *
     * Overlap formula: (booking.startTime < requestedEnd) AND (booking.endTime > requestedStart)
     *
     * @param carIds    Candidate car IDs
     * @param startTime Requested range start
     * @param endTime   Requested range end
     * @return Car IDs with at least one overlapping blocking booking
     */
    @Query("SELECT DISTINCT b.car.id FROM Booking b " +
           "WHERE b.car.id IN :carIds " +
           "AND b.status IN ('PENDING_APPROVAL', 'ACTIVE', 'CHECK_IN_OPEN', " +
           "    'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE', 'IN_TRIP') " +
           "AND b.startTime < :endTime AND b.endTime > :startTime")
    List<Long> findCarIdsWithOverlappingBookings(
            @Param("carIds") Collection<Long> carIds,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );
}
