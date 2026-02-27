package org.example.rentoza.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * C1 FIX: Repository for notification delivery outbox.
 * Supports retry queries and delivery status tracking.
 */
@Repository
public interface NotificationDeliveryOutboxRepository extends JpaRepository<NotificationDeliveryOutbox, Long> {

    /**
     * Find outbox entries ready for retry (PENDING with next_attempt_at <= now).
     */
    @Query("SELECT o FROM NotificationDeliveryOutbox o " +
            "WHERE o.status = 'PENDING' AND (o.nextAttemptAt IS NULL OR o.nextAttemptAt <= :now) " +
            "ORDER BY o.createdAt ASC")
    List<NotificationDeliveryOutbox> findPendingForRetry(@Param("now") Instant now);

    /**
     * Count dead-lettered entries for alerting.
     */
    long countByStatus(NotificationDeliveryOutbox.DeliveryStatus status);

    /**
     * Find dead-lettered entries for admin review.
     */
    List<NotificationDeliveryOutbox> findByStatusOrderByCreatedAtDesc(
            NotificationDeliveryOutbox.DeliveryStatus status);

    /**
     * Find delivery status for a specific notification.
     */
    List<NotificationDeliveryOutbox> findByNotificationId(Long notificationId);
}
