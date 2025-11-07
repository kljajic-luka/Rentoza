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
}
