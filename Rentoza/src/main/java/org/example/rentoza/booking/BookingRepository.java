package org.example.rentoza.booking;

import org.example.rentoza.car.Car;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    @Query("SELECT b FROM Booking b JOIN FETCH b.renter JOIN FETCH b.car WHERE b.car.id = :carId")
    List<Booking> findByCarId(@Param("carId") Long carId);
    List<Booking> findByRenterEmailIgnoreCase(String email);

    @Query("SELECT b FROM Booking b JOIN FETCH b.renter JOIN FETCH b.car WHERE b.car = :car")
    List<Booking> findByCar(@Param("car") Car car);

    List<Booking> findByCarIdAndEndDateAfterAndStartDateBefore(Long carId, LocalDate start, LocalDate end);

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

    long countByRenterIdAndStatus(Long renterId, BookingStatus status);

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
           "ORDER BY b.startDate DESC")
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
     * Check if there are any confirmed bookings overlapping with the given date range for a car.
     * Used for validation when blocking dates or creating new bookings.
     */
    @Query("SELECT COUNT(b) > 0 FROM Booking b WHERE b.car.id = :carId " +
           "AND b.status IN ('ACTIVE', 'CONFIRMED') " +
           "AND b.startDate <= :endDate AND b.endDate >= :startDate")
    boolean existsOverlappingBookings(
            @Param("carId") Long carId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Find all bookings that are overdue (end date in the past) but not yet marked as COMPLETED.
     * Used by scheduled task to auto-complete bookings.
     */
    @Query("SELECT b FROM Booking b " +
           "WHERE b.status = 'ACTIVE' " +
           "AND b.endDate < :currentDate")
    List<Booking> findOverdueBookings(@Param("currentDate") LocalDate currentDate);

    /**
     * Phase 2.3: Find all confirmed bookings for a car that overlap with the given date range.
     * Used for real-time conflict detection before creating a booking.
     */
    @Query("SELECT b FROM Booking b WHERE b.car.id = :carId " +
           "AND b.status IN ('ACTIVE', 'CONFIRMED') " +
           "AND b.startDate <= :endDate AND b.endDate >= :startDate")
    List<Booking> findByCarIdAndDateRange(
            @Param("carId") Long carId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
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
           "ORDER BY b.startDate DESC")
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
     * - Eagerly loads Car (brand, model, year, imageUrl, imageUrls)
     * - Eagerly loads Renter (for RLS check)
     * - Eagerly loads Owner (for RLS check)
     * 
     * Note: Car.imageUrls is @ElementCollection(fetch = EAGER) so automatically loaded.
     * 
     * @param id Booking ID
     * @return Optional containing booking with all conversation-view dependencies
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.car c " +
           "JOIN FETCH b.renter r " +
           "LEFT JOIN FETCH c.owner o " +
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
    List<Booking> findPendingBookingsBefore(@Param("threshold") java.time.LocalDateTime threshold);

    /**
     * Check for conflicting bookings excluding PENDING_APPROVAL and DECLINED.
     * Used for availability checks during approval to prevent race conditions.
     * Only considers ACTIVE and COMPLETED bookings as blocking.
     * 
     * @param carId Car ID
     * @param startDate Booking start date
     * @param endDate Booking end date
     * @return true if there are conflicting bookings
     */
    @Query("SELECT COUNT(b) > 0 FROM Booking b WHERE b.car.id = :carId " +
           "AND b.status IN ('ACTIVE', 'COMPLETED') " +
           "AND b.startDate <= :endDate AND b.endDate >= :startDate")
    boolean existsConflictingBookings(
            @Param("carId") Long carId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Find bookings for public calendar view (excludes pending/declined).
     * Returns only ACTIVE and COMPLETED bookings to show unavailable dates.
     * PENDING_APPROVAL bookings are not shown to prevent blocking dates speculatively.
     * 
     * @param carId Car ID
     * @return List of confirmed bookings for calendar display
     */
    @Query("SELECT b FROM Booking b " +
           "WHERE b.car.id = :carId " +
           "AND b.status IN ('ACTIVE', 'COMPLETED') " +
           "ORDER BY b.startDate ASC")
    List<Booking> findPublicBookingsForCar(@Param("carId") Long carId);
}
