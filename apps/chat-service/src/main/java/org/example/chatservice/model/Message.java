package org.example.chatservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "messages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;  // BIGINT in SQL

    @Column(nullable = false, length = 2000)
    private String content;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime timestamp;

    @Column
    private String mediaUrl;

    /**
     * Comma-separated moderation flags (e.g., "URL_DETECTED,POSSIBLE_OBFUSCATION").
     * Null/empty if no flags. Used by admin review queue.
     */
    @Column(name = "moderation_flags")
    private String moderationFlags;

    // Users who have read this message (proper @OneToMany relationship)
    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "message_id")
    @Builder.Default
    private Set<MessageReadReceipt> readReceipts = new HashSet<>();

    /**
     * Mark this message as read by a user.
     * @param userId User ID (BIGINT from users.id)
     */
    public void markAsReadBy(Long userId) {
        if (this.readReceipts == null) {
            this.readReceipts = new HashSet<>();
        }
        // Check if a receipt for this user already exists to avoid duplicates
        boolean alreadyRead = this.readReceipts.stream().anyMatch(r -> r.getUserId().equals(userId));
        if (!alreadyRead) {
            this.readReceipts.add(MessageReadReceipt.create(this.id, userId));
        }
    }

    /**
     * Check if this message has been read by a specific user.
     * @param userId User ID (BIGINT from users.id)
     */
    public boolean isReadBy(Long userId) {
        return this.readReceipts != null &&
               this.readReceipts.stream().anyMatch(r -> r.getUserId().equals(userId));
    }

    /**
     * Get set of user IDs who have read this message (for backward compatibility)
     * @return Set of Long user IDs
     */
    public Set<Long> getReadBy() {
        if (this.readReceipts == null) {
            return new HashSet<>();
        }
        return this.readReceipts.stream()
                .map(MessageReadReceipt::getUserId)
                .collect(Collectors.toSet());
    }
}
