package org.example.rentoza.booking.checkout.saga;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for CheckoutSagaState persistence.
 */
@Repository
public interface CheckoutSagaStateRepository extends JpaRepository<CheckoutSagaState, Long> {

    /**
     * Find saga by unique ID.
     */
    Optional<CheckoutSagaState> findBySagaId(UUID sagaId);

    /**
     * Find active saga for a booking.
     * P1 FIX: Include SUSPENDED status so resumeSuspendedSaga() can find suspended sagas.
     */
    @Query("""
        SELECT s FROM CheckoutSagaState s
        WHERE s.bookingId = :bookingId
        AND s.status IN ('PENDING', 'RUNNING', 'COMPENSATING', 'SUSPENDED')
        ORDER BY s.createdAt DESC
        """)
    Optional<CheckoutSagaState> findActiveSagaForBooking(@Param("bookingId") Long bookingId);

    /**
     * Find all sagas for a booking.
     */
    List<CheckoutSagaState> findByBookingIdOrderByCreatedAtDesc(Long bookingId);

    /**
     * Find sagas by status.
     */
    List<CheckoutSagaState> findByStatus(CheckoutSagaState.SagaStatus status);

    /**
     * Find stuck sagas (running for too long).
     * C-4 FIX: PESSIMISTIC_WRITE + SKIP LOCKED prevents contention with active saga execution.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        SELECT s FROM CheckoutSagaState s
        WHERE s.status = 'RUNNING'
        AND s.updatedAt < :threshold
        ORDER BY s.updatedAt ASC
        """)
    List<CheckoutSagaState> findStuckSagas(@Param("threshold") Instant threshold);

    /**
     * Find failed sagas eligible for retry.
     * C-4 FIX: PESSIMISTIC_WRITE + SKIP LOCKED prevents contention with active saga execution.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        SELECT s FROM CheckoutSagaState s
        WHERE s.status = 'FAILED'
        AND s.retryCount < 3
        ORDER BY s.updatedAt ASC
        """)
    List<CheckoutSagaState> findRetryableSagas();

    /**
     * Find sagas needing compensation.
     * C-4 FIX: PESSIMISTIC_WRITE + SKIP LOCKED prevents contention with active saga execution.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        SELECT s FROM CheckoutSagaState s
        WHERE s.status = 'COMPENSATING'
        ORDER BY s.updatedAt ASC
        """)
    List<CheckoutSagaState> findSagasNeedingCompensation();

        @Query("SELECT s FROM CheckoutSagaState s JOIN Booking b ON s.bookingId = b.id " +
            "WHERE s.status = 'SUSPENDED' " +
            "AND b.securityDepositHoldUntil IS NOT NULL " +
            "AND b.securityDepositHoldUntil < :now")
        List<CheckoutSagaState> findSuspendedWithExpiredHold(@Param("now") Instant now);

    /**
     * Check if booking has completed saga.
     */
    boolean existsByBookingIdAndStatus(Long bookingId, CheckoutSagaState.SagaStatus status);

    /**
     * Count sagas by status for monitoring.
     */
    @Query("""
        SELECT s.status, COUNT(s)
        FROM CheckoutSagaState s
        GROUP BY s.status
        """)
    List<Object[]> countByStatus();
}
