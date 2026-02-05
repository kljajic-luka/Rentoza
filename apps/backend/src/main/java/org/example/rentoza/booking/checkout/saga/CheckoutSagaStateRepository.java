package org.example.rentoza.booking.checkout.saga;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
     */
    @Query("""
        SELECT s FROM CheckoutSagaState s
        WHERE s.bookingId = :bookingId
        AND s.status IN ('PENDING', 'RUNNING', 'COMPENSATING')
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
     */
    @Query("""
        SELECT s FROM CheckoutSagaState s
        WHERE s.status = 'RUNNING'
        AND s.updatedAt < :threshold
        ORDER BY s.updatedAt ASC
        """)
    List<CheckoutSagaState> findStuckSagas(@Param("threshold") Instant threshold);

    /**
     * Find failed sagas eligible for retry.
     */
    @Query("""
        SELECT s FROM CheckoutSagaState s
        WHERE s.status = 'FAILED'
        AND s.retryCount < 3
        ORDER BY s.updatedAt ASC
        """)
    List<CheckoutSagaState> findRetryableSagas();

    /**
     * Find sagas needing compensation.
     */
    @Query("""
        SELECT s FROM CheckoutSagaState s
        WHERE s.status = 'COMPENSATING'
        ORDER BY s.updatedAt ASC
        """)
    List<CheckoutSagaState> findSagasNeedingCompensation();

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
