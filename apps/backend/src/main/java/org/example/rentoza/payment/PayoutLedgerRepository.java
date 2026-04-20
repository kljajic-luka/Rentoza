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
     *
     * <p><b>H-9 FIX:</b> Uses native query with {@code FOR UPDATE SKIP LOCKED} to prevent
     * duplicate payout execution if two scheduler pods race on lock release. Previously,
     * this was a JPQL query with a comment claiming SKIP LOCKED but not actually using it.
     * The distributed lock in {@link PaymentLifecycleScheduler} is the primary guard, but
     * this provides defense-in-depth at the DB level. {@code @Version} on PayoutLedger
     * provides a tertiary optimistic locking guard.
     */
    @Query(value = """
           SELECT * FROM payout_ledger p
           WHERE p.status = 'ELIGIBLE'
             AND p.on_hold = false
             AND (p.next_retry_at IS NULL OR p.next_retry_at <= :now)
             AND p.eligible_at <= :now
             AND p.attempt_count < p.max_attempts
           ORDER BY p.eligible_at ASC
           FOR UPDATE SKIP LOCKED
           """, nativeQuery = true)
    List<PayoutLedger> findEligibleForPayout(@Param("now") Instant now);

    /**
     * Stale PROCESSING rows — started but never resolved (scheduler crash recovery).
     *
     * <p><b>P1-FIX:</b> Excludes rows where {@code providerReference IS NOT NULL},
     * because those represent async payouts accepted by the bank (PENDING) that are
     * legitimately waiting for a PAYOUT.COMPLETED webhook. Only rows without a
     * provider reference indicate a crash before the provider call returned.
     */
    @Query("""
           SELECT p FROM PayoutLedger p
           WHERE p.status = 'PROCESSING'
             AND p.updatedAt < :staleBefore
             AND p.providerReference IS NULL
           """)
    List<PayoutLedger> findStaleProcessing(@Param("staleBefore") Instant staleBefore);

    /**
     * Async payouts stuck in PROCESSING with a provider reference for too long.
     * These are payouts where the bank accepted the transfer but the webhook
     * never arrived — escalated to MANUAL_REVIEW instead of re-queued.
     */
    @Query("""
           SELECT p FROM PayoutLedger p
           WHERE p.status = 'PROCESSING'
             AND p.updatedAt < :staleBefore
             AND p.providerReference IS NOT NULL
           """)
    List<PayoutLedger> findStaleAsyncProcessing(@Param("staleBefore") Instant staleBefore);

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

    /**
     * P1-FIX: FAILED payouts eligible for retry (from webhook PAYOUT.FAILED).
     * Finds payouts that failed but still have retry budget and whose backoff has elapsed.
     * Without this query, webhook-failed payouts remain in FAILED indefinitely.
     */
    @Query("""
           SELECT p FROM PayoutLedger p
           WHERE p.status = 'FAILED'
             AND p.onHold = false
             AND p.attemptCount < p.maxAttempts
             AND (p.nextRetryAt IS NULL OR p.nextRetryAt <= :now)
           """)
    List<PayoutLedger> findRetryEligibleFailedPayouts(@Param("now") Instant now);

    /**
     * P1-FIX: FAILED payouts that have exhausted their retry budget.
     * These need escalation to MANUAL_REVIEW since no automatic recovery is possible.
     */
    @Query("""
           SELECT p FROM PayoutLedger p
           WHERE p.status = 'FAILED'
             AND p.attemptCount >= p.maxAttempts
           """)
    List<PayoutLedger> findExhaustedFailedPayouts();

    // ── Tax withholding queries ────────────────────────────────────────────

    /**
     * Find all completed payouts for a host within a date range (for monthly tax aggregation).
     */
    @Query("""
           SELECT p FROM PayoutLedger p
           WHERE p.hostUserId = :hostUserId
             AND p.paidAt >= :start
             AND p.paidAt < :end
             AND p.status = 'COMPLETED'
           """)
    List<PayoutLedger> findByHostUserIdAndPaidAtBetween(
            @Param("hostUserId") Long hostUserId,
            @Param("start") Instant start,
            @Param("end") Instant end);

    /**
     * Find distinct host user IDs with completed payouts in a date range.
     */
    @Query("""
           SELECT DISTINCT p.hostUserId FROM PayoutLedger p
           WHERE p.paidAt >= :start
             AND p.paidAt < :end
             AND p.status = 'COMPLETED'
           """)
    List<Long> findDistinctHostUserIdsByPaidAtBetween(
            @Param("start") Instant start,
            @Param("end") Instant end);

    /**
     * Find distinct individual-owner host user IDs with completed payouts in a date range.
     * Used for PPPPD aggregation (only individual owners have income tax withholding).
     */
    @Query("""
           SELECT DISTINCT p.hostUserId FROM PayoutLedger p
           WHERE p.paidAt >= :start
             AND p.paidAt < :end
             AND p.status = 'COMPLETED'
             AND p.ownerTaxType = 'INDIVIDUAL'
           """)
    List<Long> findDistinctIndividualHostUserIdsByPaidAtBetween(
            @Param("start") Instant start,
            @Param("end") Instant end);
}
