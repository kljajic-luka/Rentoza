package org.example.rentoza.availability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for managing blocked date ranges for cars.
 */
@Repository
public interface BlockedDateRepository extends JpaRepository<BlockedDate, Long> {

    /**
     * Find all blocked date ranges for a specific car.
     * Useful for displaying the owner's calendar and checking availability.
     */
    List<BlockedDate> findByCarIdOrderByStartDateAsc(Long carId);

    /**
     * Find all blocked date ranges for a specific car owned by a specific user.
     * Used for authorization checks to ensure only the owner can modify blocked dates.
     */
    List<BlockedDate> findByCarIdAndOwnerId(Long carId, Long ownerId);

    /**
     * Check if there are any blocked dates overlapping with the given date range for a car.
     * Used for validation when creating new blocked ranges or bookings.
     */
    @Query("SELECT COUNT(b) > 0 FROM BlockedDate b WHERE b.car.id = :carId " +
           "AND b.startDate <= :endDate AND b.endDate >= :startDate")
    boolean existsOverlappingBlockedDates(
            @Param("carId") Long carId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Find all blocked dates that overlap with a specific date range.
     * Useful for detailed overlap detection and error messaging.
     */
    @Query("SELECT b FROM BlockedDate b WHERE b.car.id = :carId " +
           "AND b.startDate <= :endDate AND b.endDate >= :startDate")
    List<BlockedDate> findOverlappingBlockedDates(
            @Param("carId") Long carId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Delete all blocked dates for a specific car (useful for car deletion cascade).
     */
    void deleteByCarId(Long carId);
}
