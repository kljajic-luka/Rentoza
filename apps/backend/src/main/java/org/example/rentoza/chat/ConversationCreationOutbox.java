package org.example.rentoza.chat;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(
        name = "conversation_creation_outbox",
        indexes = {
                @Index(name = "idx_conv_outbox_status_next_attempt", columnList = "status, next_attempt_at"),
                @Index(name = "idx_conv_outbox_booking", columnList = "booking_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationCreationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false, length = 64)
    private String bookingId;

    @Column(name = "renter_id", nullable = false, length = 64)
    private String renterId;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private int maxAttempts = 5;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    @Builder.Default
    private Instant nextAttemptAt = Instant.now();

    public enum Status {
        PENDING,
        CREATED,
        FAILED
    }

    public void recordFailure(String errorMessage) {
        this.attemptCount++;
        this.lastError = errorMessage != null && errorMessage.length() > 500
                ? errorMessage.substring(0, 500)
                : errorMessage;

        if (this.attemptCount >= this.maxAttempts) {
            this.status = Status.FAILED;
            this.nextAttemptAt = Instant.now();
            return;
        }

        long backoffSeconds = 30L * (long) Math.pow(4, Math.max(this.attemptCount - 1, 0));
        this.status = Status.PENDING;
        this.nextAttemptAt = Instant.now().plusSeconds(backoffSeconds);
    }

    public void markCreated() {
        this.status = Status.CREATED;
        this.lastError = null;
        this.nextAttemptAt = Instant.now();
    }
}