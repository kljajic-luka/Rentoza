package org.example.rentoza.notification;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * C1 FIX: Transactional outbox entity for durable notification delivery.
 *
 * Each row represents a pending delivery attempt for a specific channel.
 * Written in the same transaction as the notification itself, ensuring
 * delivery intents survive application crashes.
 *
 * Lifecycle: PENDING -> DELIVERED | FAILED (after max retries) | DEAD_LETTER
 */
@Entity
@Table(
        name = "notification_delivery_outbox",
        indexes = {
                @Index(name = "idx_outbox_status_next_attempt", columnList = "status, next_attempt_at"),
                @Index(name = "idx_outbox_notification", columnList = "notification_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationDeliveryOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Column(name = "channel_name", nullable = false, length = 30)
    private String channelName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private int maxAttempts = 3;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    public enum DeliveryStatus {
        PENDING,
        DELIVERED,
        FAILED,
        DEAD_LETTER
    }

    /**
     * Record a successful delivery.
     */
    public void markDelivered() {
        this.status = DeliveryStatus.DELIVERED;
        this.deliveredAt = Instant.now();
    }

    /**
     * Record a failed attempt with exponential backoff.
     */
    public void markAttemptFailed(String error) {
        this.attemptCount++;
        this.lastError = error != null && error.length() > 500 ? error.substring(0, 500) : error;

        if (this.attemptCount >= this.maxAttempts) {
            this.status = DeliveryStatus.DEAD_LETTER;
        } else {
            // Exponential backoff: 30s, 120s, 480s
            long backoffSeconds = 30L * (long) Math.pow(4, this.attemptCount - 1);
            this.nextAttemptAt = Instant.now().plusSeconds(backoffSeconds);
        }
    }
}
