package org.example.chatservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "conversations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;  // BIGINT in SQL

    @Column(name = "renter_id", nullable = false)
    private Long renterId;  // BIGINT in SQL

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;   // BIGINT in SQL

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversationStatus status;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime lastMessageAt;

    /**
     * Check if a user is a participant in this conversation.
     * @param userId User ID (BIGINT from users.id)
     */
    public boolean isParticipant(Long userId) {
        return Objects.equals(renterId, userId) || Objects.equals(ownerId, userId);
    }

    // Check if conversation allows messaging
    public boolean isMessagingAllowed() {
        return status == ConversationStatus.PENDING
                || status == ConversationStatus.ACTIVE
                || status == ConversationStatus.CLOSED;
    }
}
