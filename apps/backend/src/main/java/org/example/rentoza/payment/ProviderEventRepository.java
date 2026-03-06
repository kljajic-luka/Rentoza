package org.example.rentoza.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface ProviderEventRepository extends JpaRepository<ProviderEvent, Long> {

    Optional<ProviderEvent> findByProviderEventId(String providerEventId);

    boolean existsByProviderEventId(String providerEventId);

    /** Stored webhook events that were never fully processed and are old enough to replay. */
    @Query("""
           SELECT e FROM ProviderEvent e
           WHERE e.processedAt IS NULL
             AND e.receivedAt < :before
           ORDER BY e.receivedAt ASC
           """)
    java.util.List<ProviderEvent> findReplayable(@Param("before") Instant before);
}
