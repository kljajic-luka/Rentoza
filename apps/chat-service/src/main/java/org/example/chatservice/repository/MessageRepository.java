package org.example.chatservice.repository;

import org.example.chatservice.model.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    Page<Message> findByConversationIdOrderByTimestampDesc(Long conversationId, Pageable pageable);

    // Get all messages for a conversation (for admin transcript review)
    List<Message> findByConversationIdOrderByTimestampAsc(Long conversationId);

    // Get the latest message for a conversation (for preview in list)
    Message findFirstByConversationIdOrderByTimestampDesc(Long conversationId);

    @Query("SELECT COUNT(m) FROM Message m " +
           "WHERE m.conversationId = :conversationId " +
           "AND m.senderId != :userId " +
           "AND NOT EXISTS (SELECT 1 FROM MessageReadReceipt r WHERE r.messageId = m.id AND r.userId = :userId)")
    long countUnreadMessages(@Param("conversationId") Long conversationId, @Param("userId") Long userId);

    /**
     * Find messages with moderation flags pending review (admin review queue).
     * D3 FIX: Excludes messages that have already been reviewed (reviewOutcome is not null).
     */
    @Query("SELECT m FROM Message m WHERE m.moderationFlags IS NOT NULL AND m.moderationFlags != '' AND m.reviewOutcome IS NULL ORDER BY m.timestamp DESC")
    Page<Message> findFlaggedMessages(Pageable pageable);

    /**
     * Count messages with moderation flags pending review (for dashboard badge).
     * D3 FIX: Only counts unreviewed flagged messages.
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.moderationFlags IS NOT NULL AND m.moderationFlags != '' AND m.reviewOutcome IS NULL")
    long countFlaggedMessages();

    // ========== GDPR COMPLIANCE (GAP-3 REMEDIATION) ==========

    /**
     * Find all messages sent by a user across all conversations.
     * Used for GDPR Article 15 data export.
     */
    List<Message> findBySenderIdOrderByTimestampAsc(Long senderId);

    /**
     * Anonymize all messages from a deleted user.
     * Replaces content with a deletion notice and sender with sentinel value (0).
     * Used by GDPR Article 17 erasure propagation from main backend.
     *
     * @return number of messages anonymized
     */
    @Modifying
    @Query("UPDATE Message m SET m.content = '[Message from deleted user]', m.senderId = 0, m.mediaUrl = NULL WHERE m.senderId = :userId")
    int anonymizeMessagesBySenderId(@Param("userId") Long userId);
}
