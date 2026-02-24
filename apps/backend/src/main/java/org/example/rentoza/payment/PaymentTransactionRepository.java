package org.example.rentoza.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    List<PaymentTransaction> findByBookingId(Long bookingId);

    /** Fetch transactions eligible for retry (failed retryable, not at max attempts). */
    @Query("""
        SELECT t FROM PaymentTransaction t
        WHERE t.status IN ('FAILED_RETRYABLE', 'PROCESSING')
          AND t.attemptCount < t.maxAttempts
          AND (t.nextRetryAt IS NULL OR t.nextRetryAt <= :now)
        ORDER BY t.createdAt ASC
        """)
    List<PaymentTransaction> findRetryEligible(@Param("now") Instant now);

    /** Fetch PROCESSING records stuck for longer than the stale timeout. */
    @Query("""
        SELECT t FROM PaymentTransaction t
        WHERE t.status = 'PROCESSING'
          AND t.updatedAt < :staleBefore
        """)
    List<PaymentTransaction> findStaleProcessing(@Param("staleBefore") Instant staleBefore);

    Optional<PaymentTransaction> findByBookingIdAndOperation(
            Long bookingId, PaymentTransaction.PaymentOperation operation);

    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * Find a transaction by the provider-assigned authorization ID.
     * Used by the webhook handler to locate the exact transaction that a provider
     * callback refers to (P0-4: transaction-scoped webhook processing).
     */
    Optional<PaymentTransaction> findByProviderAuthId(String providerAuthId);
}
