package org.example.chatservice.repository;

import org.example.chatservice.model.MessageReadReceipt;
import org.example.chatservice.model.MessageReadReceiptId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Repository for MessageReadReceipt entity.
 * 
 * Handles persistence operations for read receipts with composite key
 * (messageId, userId).
 * Provides efficient batch operations for read receipt management.
 * Replaces the N+1 anti-pattern from @ElementCollection.
 * 
 * Note: Generic type is JpaRepository<MessageReadReceipt, MessageReadReceiptId>
 * because the entity uses a composite primary key via @IdClass.
 */
@Repository
public interface MessageReadReceiptRepository extends JpaRepository<MessageReadReceipt, MessageReadReceiptId> {

    /**
     * Check if a specific user has read a specific message.
     */
    boolean existsByMessageIdAndUserId(Long messageId, Long userId);

    /**
     * Get read receipt for specific message and user.
     */
    Optional<MessageReadReceipt> findByMessageIdAndUserId(Long messageId, Long userId);

    /**
     * Get all read receipts for a message (who read it).
     */
    List<MessageReadReceipt> findByMessageId(Long messageId);

    /**
     * Get all user IDs who read a specific message.
     */
    @Query("SELECT mrr.userId FROM MessageReadReceipt mrr WHERE mrr.messageId = :messageId")
    Set<String> findUserIdsWhoReadMessage(@Param("messageId") Long messageId);

    /**
     * Get all message IDs read by a specific user.
     * Used for efficient unread count queries.
     */
    @Query("SELECT mrr.messageId FROM MessageReadReceipt mrr WHERE mrr.userId = :userId")
    Set<Long> findMessageIdsReadByUser(@Param("userId") Long userId);

    /**
     * Count unread messages in a conversation for a user.
     * Use native query for performance on large datasets.
     */
    @Query(value = """
            SELECT COUNT(m.id)
            FROM messages m
            WHERE m.conversation_id = :conversationId
              AND m.sender_id != :userId
              AND NOT EXISTS (
                  SELECT 1 FROM message_read_by mrr
                  WHERE mrr.message_id = m.id AND mrr.user_id = :userId
              )
            """, nativeQuery = true)
    long countUnreadMessagesInConversation(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId);

    /**
     * Get all unread message IDs in a conversation for a user.
     * Used for batch mark-as-read operation.
     */
    @Query(value = """
            SELECT m.id
            FROM messages m
            WHERE m.conversation_id = :conversationId
              AND m.sender_id != :userId
              AND NOT EXISTS (
                  SELECT 1 FROM message_read_by mrr
                  WHERE mrr.message_id = m.id AND mrr.user_id = :userId
              )
            """, nativeQuery = true)
    List<Long> findUnreadMessageIdsInConversation(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId);

    /**
     * Delete all read receipts for a message.
     * Used when a message is deleted.
     */
    @Modifying
    @Query("DELETE FROM MessageReadReceipt mrr WHERE mrr.messageId = :messageId")
    void deleteByMessageId(@Param("messageId") Long messageId);

    /**
     * Delete all read receipts for a user (GDPR right to be forgotten).
     */
    @Modifying
    @Query("DELETE FROM MessageReadReceipt mrr WHERE mrr.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
