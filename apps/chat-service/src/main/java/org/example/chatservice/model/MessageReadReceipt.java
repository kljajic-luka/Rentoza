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
 * Uses composite primary key (messageId, userId) to match database schema:
 * - Primary Key: (message_id, user_id) - Composite key
 * - A user can read a message only once
 * - Timestamp records when the read occurred
 * 
 * Database schema:
 * CREATE TABLE message_read_by (
 * message_id BIGINT PRIMARY KEY,
 * user_id BIGINT PRIMARY KEY,
 * read_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
 * );
 * 
 * This replaces the @ElementCollection anti-pattern with a proper normalized
 * table.
 * Benefits:
 * - Proper timestamp tracking (when user read the message)
 * - Efficient batch operations
 * - No N+1 queries
 * - Supports future features like "read at" timestamps in UI
 * - Correct JPA mapping with composite key via @IdClass
 */
@Entity
@Table(name = "message_read_by")
@IdClass(MessageReadReceiptId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageReadReceipt {

    /**
     * Message ID - Part of composite primary key.
     * Foreign key reference to Message.id
     */
    @Id
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    /**
     * User ID - Part of composite primary key.
     * References user ID from the main service
     */
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Timestamp when this read receipt was created.
     * Automatically set to current timestamp on insert.
     */
    @CreationTimestamp
    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;

    /**
     * Lazy-loaded relationship to the Message entity.
     * Allows accessing message details if needed.
     * Uses insertable=false, updatable=false because messageId is part of composite
     * key.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", insertable = false, updatable = false)
    private Message message;

    /**
     * Factory method to create a new read receipt.
     * 
     * @param messageId The ID of the message being read (BIGINT)
     * @param userId    The ID of the user reading the message (BIGINT)
     * @return A new MessageReadReceipt instance with composite key set
     */
    public static MessageReadReceipt create(Long messageId, Long userId) {
        return MessageReadReceipt.builder()
                .messageId(messageId)
                .userId(userId)
                .readAt(LocalDateTime.now())  // ✅ Explicitly set to avoid NOT NULL constraint violation
                .build();
    }
}
