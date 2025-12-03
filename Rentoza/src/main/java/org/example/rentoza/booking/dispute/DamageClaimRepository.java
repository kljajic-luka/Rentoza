package org.example.rentoza.booking.dispute;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for damage claims.
 */
public interface DamageClaimRepository extends JpaRepository<DamageClaim, Long> {

    /**
     * Find claim by booking ID.
     */
    Optional<DamageClaim> findByBookingId(Long bookingId);

    /**
     * Check if a claim exists for a booking.
     */
    boolean existsByBookingId(Long bookingId);

    /**
     * Find claims pending guest response.
     */
    @Query("SELECT c FROM DamageClaim c WHERE c.status = 'PENDING' ORDER BY c.createdAt ASC")
    List<DamageClaim> findPending();

    /**
     * Find claims awaiting admin review (disputed).
     */
    @Query("SELECT c FROM DamageClaim c WHERE c.status = 'DISPUTED' ORDER BY c.createdAt ASC")
    List<DamageClaim> findAwaitingAdminReview();

    /**
     * Find claims that have passed response deadline and need auto-approval.
     */
    @Query("SELECT c FROM DamageClaim c " +
           "WHERE c.status = 'PENDING' AND c.responseDeadline < :now")
    List<DamageClaim> findExpiredPendingClaims(@Param("now") Instant now);

    /**
     * Find claims by host.
     */
    @Query("SELECT c FROM DamageClaim c WHERE c.host.id = :hostId ORDER BY c.createdAt DESC")
    List<DamageClaim> findByHostId(@Param("hostId") Long hostId);

    /**
     * Find claims by guest.
     */
    @Query("SELECT c FROM DamageClaim c WHERE c.guest.id = :guestId ORDER BY c.createdAt DESC")
    List<DamageClaim> findByGuestId(@Param("guestId") Long guestId);

    /**
     * Find claims by status.
     */
    List<DamageClaim> findByStatus(DamageClaimStatus status);

    /**
     * Count claims requiring admin attention.
     */
    @Query("SELECT COUNT(c) FROM DamageClaim c WHERE c.status = 'DISPUTED'")
    long countAwaitingReview();
}


