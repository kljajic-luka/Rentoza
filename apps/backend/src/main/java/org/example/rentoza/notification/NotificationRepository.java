package org.example.rentoza.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for managing Notification entities.
 * Includes optimized queries for fetching user notifications and cleanup operations.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Find all notifications for a specific user, ordered by creation date (newest first).
     * Uses pagination to avoid loading too many notifications at once.
     */
    Page<Notification> findByRecipientIdOrderByCreatedAtDesc(Long recipientId, Pageable pageable);

    /**
     * Find unread notifications for a specific user.
     */
    List<Notification> findByRecipientIdAndReadFalseOrderByCreatedAtDesc(Long recipientId);

    /**
     * Count unread notifications for a user.
     */
    long countByRecipientIdAndReadFalse(Long recipientId);

    /**
     * Mark all notifications as read for a specific user.
     */
    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipient.id = :recipientId AND n.read = false")
    int markAllAsReadForUser(@Param("recipientId") Long recipientId);

    /**
        * Soft-delete notifications older than the specified instant.
        * Used by scheduled cleanup task to retain evidence while hiding expired rows.
     */
    @Modifying
        @Query("UPDATE Notification n SET n.deletedAt = :expirationDate WHERE n.createdAt < :expirationDate AND n.deletedAt IS NULL")
        int softDeleteExpiredNotifications(@Param("expirationDate") Instant expirationDate);

    /**
     * Find notifications by type and related entity ID.
     * Useful for preventing duplicate notifications for the same event.
     */
    List<Notification> findByTypeAndRelatedEntityId(NotificationType type, String relatedEntityId);
}
