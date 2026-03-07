package org.example.rentoza.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationCreationOutboxRepository extends JpaRepository<ConversationCreationOutbox, Long> {

    @Query("SELECT o FROM ConversationCreationOutbox o " +
            "WHERE o.status = 'PENDING' AND o.nextAttemptAt <= :now ORDER BY o.createdAt ASC")
    List<ConversationCreationOutbox> findPendingForRetry(@Param("now") Instant now);

    Optional<ConversationCreationOutbox> findFirstByBookingIdAndStatusIn(
            String bookingId,
            List<ConversationCreationOutbox.Status> statuses);
}