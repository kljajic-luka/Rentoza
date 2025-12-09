package org.example.chatservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Entity representing a read receipt for a message.
 * 
 * This replaces the @ElementCollection anti-pattern with a proper normalized table.
 * Benefits:
 * - Proper timestamp tracking (when user read the message)
 * - Efficient batch operations
 * - No N+1 queries
 * - Supports future features like "read at" timestamps in UI
 */
@Entity
@Table(name = "message_read_receipts", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "user_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageReadReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @CreationTimestamp
    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;

    /**
     * Factory method to create a read receipt.
     */
    public static MessageReadReceipt create(Long messageId, String userId) {
        return MessageReadReceipt.builder()
                .messageId(messageId)
                .userId(userId)
                .build();
    }
}
