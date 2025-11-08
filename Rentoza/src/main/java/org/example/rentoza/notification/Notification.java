package org.example.rentoza.notification;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Entity representing a user notification.
 * Notifications are delivered via multiple channels: WebSocket (in-app), Email, and Push.
 * Notifications auto-expire after 30 days via scheduled cleanup task.
 */
@Entity
@Table(
        name = "notifications",
        indexes = {
                @Index(name = "idx_notification_recipient", columnList = "recipient_id"),
                @Index(name = "idx_notification_created", columnList = "created_at"),
                @Index(name = "idx_notification_read", columnList = "read_status"),
                @Index(name = "idx_notification_recipient_read", columnList = "recipient_id, read_status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType type;

    @Column(nullable = false, length = 500, columnDefinition = "VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
    private String message;

    /**
     * ID of the related entity (booking ID, review ID, chat message ID, etc.)
     * Used for deep linking in the frontend.
     */
    @Column(name = "related_entity_id", length = 100)
    private String relatedEntityId;

    @Column(name = "read_status", nullable = false)
    private boolean read = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Helper method to mark notification as read.
     */
    public void markAsRead() {
        this.read = true;
    }
}
