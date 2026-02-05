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
public interface DamageClaimRepository extends JpaRepository<DamageClaim, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<DamageClaim> {

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

    // ========== ADMIN MANAGEMENT QUERIES ==========

    /**
     * Count claims filed against a guest (renter).
     * Used for admin user risk scoring.
     * 
     * @param guestId Guest's user ID
     * @return Total claim count against the guest
     */
    @Query("SELECT COUNT(c) FROM DamageClaim c WHERE c.guest.id = :guestId")
    long countByGuestId(@Param("guestId") Long guestId);

    /**
     * Count open disputes requiring admin attention.
     * Used for admin dashboard KPI.
     * 
     * @return Number of open disputes
     */
    @Query("SELECT COUNT(c) FROM DamageClaim c WHERE c.status IN ('PENDING', 'DISPUTED')")
    Long countOpenDisputes();
    
    // ========== VAL-004 PHASE 6: CHECK-IN DISPUTE QUERIES ==========
    
    /**
     * Find check-in dispute for a booking by dispute stage.
     * Used by timeout handler to find pending check-in disputes.
     * 
     * @param booking The booking
     * @param disputeStage The dispute stage (CHECK_IN or CHECKOUT)
     * @return Optional dispute claim
     */
    @Query("SELECT c FROM DamageClaim c WHERE c.booking = :booking AND c.disputeStage = :disputeStage " +
           "AND c.status = 'CHECK_IN_DISPUTE_PENDING' ORDER BY c.createdAt DESC")
    Optional<DamageClaim> findByBookingAndDisputeStage(
        @Param("booking") org.example.rentoza.booking.Booking booking, 
        @Param("disputeStage") DisputeStage disputeStage
    );

    // ========== BUG-007: DEPOSIT RELEASE BLOCKING ==========
    
    /**
     * Check if booking has any damage claims that block deposit release.
     * 
     * <p><b>VAL-010:</b> Deposit MUST NOT be released while any claims are:
     * <ul>
     *   <li>PENDING - Awaiting guest response</li>
     *   <li>DISPUTED - Under admin review</li>
     *   <li>ACCEPTED_BY_GUEST - Accepted but payment not yet processed</li>
     *   <li>AUTO_APPROVED - Auto-approved but payment pending</li>
     *   <li>ADMIN_APPROVED - Admin approved but payment pending</li>
     *   <li>ESCALATED - Under senior review</li>
     * </ul>
     * 
     * <p>Only PAID and ADMIN_REJECTED claims allow deposit release.
     * 
     * @param bookingId The booking ID
     * @return true if there are claims blocking deposit release
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM DamageClaim c " +
           "WHERE c.booking.id = :bookingId " +
           "AND c.status IN ('PENDING', 'DISPUTED', 'ACCEPTED_BY_GUEST', 'AUTO_APPROVED', 'ADMIN_APPROVED', 'ESCALATED')")
    boolean hasClaimsBlockingDepositRelease(@Param("bookingId") Long bookingId);

    /**
     * Get all active (non-resolved) claims for a booking.
     * Used to inform the user why deposit release is blocked.
     * 
     * @param bookingId The booking ID
     * @return List of active damage claims
     */
    @Query("SELECT c FROM DamageClaim c WHERE c.booking.id = :bookingId " +
           "AND c.status IN ('PENDING', 'DISPUTED', 'ACCEPTED_BY_GUEST', 'AUTO_APPROVED', 'ADMIN_APPROVED', 'ESCALATED') " +
           "ORDER BY c.createdAt DESC")
    List<DamageClaim> findActiveClaimsByBookingId(@Param("bookingId") Long bookingId);
}


