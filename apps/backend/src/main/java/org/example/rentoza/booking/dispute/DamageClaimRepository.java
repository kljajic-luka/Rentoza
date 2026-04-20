package org.example.rentoza.booking.dispute;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for damage claims.
 */
public interface DamageClaimRepository extends JpaRepository<DamageClaim, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<DamageClaim> {

    /**
     * C-4 FIX: Find damage claim by ID with PESSIMISTIC_WRITE lock.
     * Prevents two admins from resolving the same dispute simultaneously.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
        @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")
    })
    @Query("SELECT c FROM DamageClaim c " +
           "JOIN FETCH c.booking b " +
           "LEFT JOIN FETCH b.car " +
           "WHERE c.id = :id")
    Optional<DamageClaim> findByIdWithLock(@Param("id") Long id);

    /**
     * Find claim by booking ID (legacy single-claim lookup).
     * @deprecated Use {@link #findAllByBookingId(Long)} for multi-claim support.
     *             Kept for backward compatibility; returns the most recent claim.
     */
    @Deprecated
    Optional<DamageClaim> findByBookingId(Long bookingId);

    /**
     * Find all claims for a booking, ordered newest first.
     * Supports the multi-claim model where a booking can have
     * multiple claims (host claim + guest counter-claim, etc.).
     * 
     * @since V61 - Multi-claim support
     */
    @Query("SELECT c FROM DamageClaim c WHERE c.booking.id = :bookingId ORDER BY c.createdAt DESC")
    List<DamageClaim> findAllByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Check if a claim exists for a booking.
     */
    boolean existsByBookingId(Long bookingId);
    
    /**
     * Check if an active claim exists for a booking + dispute stage + initiator combination.
     * Used to prevent duplicate claims in the multi-claim model.
     * Only considers non-resolved statuses.
     * 
     * @since V61 - Duplicate claim prevention
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM DamageClaim c " +
           "WHERE c.booking.id = :bookingId " +
           "AND c.disputeStage = :disputeStage " +
           "AND c.initiator = :initiator " +
           "AND c.status IN ('PENDING', 'DISPUTED', 'ESCALATED', 'ACCEPTED_BY_GUEST', 'AUTO_APPROVED', " +
           "'CHECKOUT_PENDING', 'CHECKOUT_GUEST_ACCEPTED', 'CHECKOUT_GUEST_DISPUTED', " +
           "'CHECKOUT_TIMEOUT_ESCALATED', 'CHECK_IN_DISPUTE_PENDING')")
    boolean hasActiveClaim(
        @Param("bookingId") Long bookingId,
        @Param("disputeStage") DisputeStage disputeStage,
        @Param("initiator") ClaimInitiator initiator
    );

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
     *   <li>CHECKOUT_PENDING - Checkout damage awaiting guest response</li>
     *   <li>CHECKOUT_GUEST_DISPUTED - Guest disputed checkout damage</li>
     *   <li>CHECKOUT_TIMEOUT_ESCALATED - Timeout-escalated checkout dispute</li>
     *   <li>CHECK_IN_DISPUTE_PENDING - Check-in dispute awaiting resolution</li>
     * </ul>
     * 
     * <p>Only PAID, ADMIN_REJECTED, CHECKOUT_ADMIN_REJECTED, CANCELLED, and resolved check-in 
     * statuses allow deposit release.
     * 
     * @param bookingId The booking ID
     * @return true if there are claims blocking deposit release
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM DamageClaim c " +
           "WHERE c.booking.id = :bookingId " +
           "AND c.status IN ('PENDING', 'DISPUTED', 'ACCEPTED_BY_GUEST', 'AUTO_APPROVED', 'ADMIN_APPROVED', 'ESCALATED', " +
           "'CHECKOUT_PENDING', 'CHECKOUT_GUEST_ACCEPTED', 'CHECKOUT_GUEST_DISPUTED', 'CHECKOUT_TIMEOUT_ESCALATED', " +
           "'CHECK_IN_DISPUTE_PENDING')")
    boolean hasClaimsBlockingDepositRelease(@Param("bookingId") Long bookingId);

    /**
     * Get all active (non-resolved) claims for a booking.
     * Used to inform the user why deposit release is blocked.
     * 
     * @param bookingId The booking ID
     * @return List of active damage claims
     */
    @Query("SELECT c FROM DamageClaim c WHERE c.booking.id = :bookingId " +
           "AND c.status IN ('PENDING', 'DISPUTED', 'ACCEPTED_BY_GUEST', 'AUTO_APPROVED', 'ADMIN_APPROVED', 'ESCALATED', " +
           "'CHECKOUT_PENDING', 'CHECKOUT_GUEST_ACCEPTED', 'CHECKOUT_GUEST_DISPUTED', 'CHECKOUT_TIMEOUT_ESCALATED', " +
           "'CHECK_IN_DISPUTE_PENDING') " +
           "ORDER BY c.createdAt DESC")
    List<DamageClaim> findActiveClaimsByBookingId(@Param("bookingId") Long bookingId);

    // ========== TASK 5: SLA BREACH DETECTION ==========

    /**
     * Pronalazi sporove koji su probili SLA od 48h bez resenja,
     * i za koje poslednji SLA alert nije poslat u poslednjih 24h (ili nikad).
     */
    @Query("SELECT c FROM DamageClaim c " +
           "WHERE c.status IN ('PENDING', 'DISPUTED', 'CHECK_IN_DISPUTE_PENDING', " +
           "'CHECKOUT_PENDING', 'CHECKOUT_GUEST_DISPUTED') " +
           "AND c.createdAt < :slaThreshold " +
           "AND (c.lastSlaAlertSentAt IS NULL OR c.lastSlaAlertSentAt < :alertCooldown)")
    List<DamageClaim> findSlaBreachedClaims(
        @Param("slaThreshold") Instant slaThreshold,
        @Param("alertCooldown") Instant alertCooldown
    );
}


