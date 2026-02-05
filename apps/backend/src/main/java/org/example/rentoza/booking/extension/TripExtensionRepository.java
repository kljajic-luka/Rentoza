package org.example.rentoza.booking.extension;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for trip extension requests.
 */
public interface TripExtensionRepository extends JpaRepository<TripExtension, Long> {

    /**
     * Find all extensions for a booking.
     */
    List<TripExtension> findByBookingIdOrderByCreatedAtDesc(Long bookingId);

    /**
     * Find pending extension for a booking.
     */
    @Query("SELECT e FROM TripExtension e WHERE e.booking.id = :bookingId AND e.status = 'PENDING'")
    Optional<TripExtension> findPendingByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Check if booking has a pending extension.
     */
    @Query("SELECT COUNT(e) > 0 FROM TripExtension e WHERE e.booking.id = :bookingId AND e.status = 'PENDING'")
    boolean hasPendingExtension(@Param("bookingId") Long bookingId);

    /**
     * Find extensions that have expired (deadline passed without response).
     */
    @Query("SELECT e FROM TripExtension e WHERE e.status = 'PENDING' AND e.responseDeadline < :now")
    List<TripExtension> findExpiredPending(@Param("now") Instant now);

    /**
     * Find extensions for a host (all their car bookings).
     */
    @Query("SELECT e FROM TripExtension e WHERE e.booking.car.owner.id = :hostId ORDER BY e.createdAt DESC")
    List<TripExtension> findByHostId(@Param("hostId") Long hostId);

    /**
     * Find extensions for a guest.
     */
    @Query("SELECT e FROM TripExtension e WHERE e.booking.renter.id = :guestId ORDER BY e.createdAt DESC")
    List<TripExtension> findByGuestId(@Param("guestId") Long guestId);
}


