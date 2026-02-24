package org.example.rentoza.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PayoutLedgerRepository extends JpaRepository<PayoutLedger, Long> {

    Optional<PayoutLedger> findByIdempotencyKey(String idempotencyKey);

    Optional<PayoutLedger> findByBookingId(Long bookingId);

    boolean existsByIdempotencyKey(String idempotencyKey);

    // ── Scheduler queries ─────────────────────────────────────────────────────

    /**
     * Payouts that have crossed the dispute window and are ready for disbursement.
     * Skips rows locked by another pod (SKIP LOCKED = safe concurrent scheduling).
     */
    @Query("""
           SELECT p FROM PayoutLedger p
           WHERE p.status = 'ELIGIBLE'
             AND p.onHold = false
             AND (p.nextRetryAt IS NULL OR p.nextRetryAt <= :now)
             AND p.eligibleAt <= :now
             AND p.attemptCount < p.maxAttempts
           ORDER BY p.eligibleAt ASC
           """)
    List<PayoutLedger> findEligibleForPayout(@Param("now") Instant now);

    /**
     * Stale PROCESSING rows — started but never resolved (scheduler crash recovery).
     */
    @Query("""
           SELECT p FROM PayoutLedger p
           WHERE p.status = 'PROCESSING'
             AND p.updatedAt < :staleBefore
           """)
    List<PayoutLedger> findStaleProcessing(@Param("staleBefore") Instant staleBefore);

    /**
     * Payouts to mark ELIGIBLE after dispute-hold window has elapsed.
     * Source status is PENDING with eligibleAt in the past.
     */
    @Query("""
           SELECT p FROM PayoutLedger p
           WHERE p.status = 'PENDING'
             AND p.onHold = false
             AND p.eligibleAt <= :now
           """)
    List<PayoutLedger> findReadyToMarkEligible(@Param("now") Instant now);

    /**
     * Retrieve all payouts for a host — useful for host dashboard and audit.
     */
    List<PayoutLedger> findByHostUserId(Long hostUserId);
}
