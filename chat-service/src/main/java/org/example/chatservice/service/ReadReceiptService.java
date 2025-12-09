package org.example.chatservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.chatservice.model.MessageReadReceipt;
import org.example.chatservice.repository.MessageReadReceiptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for managing read receipts.
 * 
 * Replaces the N+1 anti-pattern from @ElementCollection with efficient batch operations.
 * 
 * Key improvements:
 * - Batch INSERT for marking multiple messages as read
 * - Single query for unread count (no iteration)
 * - Proper timestamp tracking
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReadReceiptService {

    private final MessageReadReceiptRepository readReceiptRepository;

    /**
     * Mark all unread messages in a conversation as read by the user.
     * 
     * Uses batch INSERT instead of individual saves (N+1 fix).
     * 
     * @param conversationId The conversation ID
     * @param userId The user marking messages as read
     * @return Number of messages marked as read
     */
    @Transactional
    public int markMessagesAsRead(Long conversationId, String userId) {
        // Get unread message IDs in a single query
        List<Long> unreadMessageIds = readReceiptRepository
                .findUnreadMessageIdsInConversation(conversationId, userId);

        if (unreadMessageIds.isEmpty()) {
            log.debug("[ReadReceipt] No unread messages for user {} in conversation {}", 
                    userId, conversationId);
            return 0;
        }

        // Create read receipts for all unread messages
        List<MessageReadReceipt> receipts = unreadMessageIds.stream()
                .map(messageId -> MessageReadReceipt.create(messageId, userId))
                .collect(Collectors.toList());

        // Batch save all receipts (single INSERT with multiple VALUES)
        readReceiptRepository.saveAll(receipts);

        log.info("[ReadReceipt] Marked {} messages as read for user {} in conversation {}", 
                receipts.size(), userId, conversationId);

        return receipts.size();
    }

    /**
     * Mark a single message as read by a user.
     * 
     * @param messageId The message ID
     * @param userId The user reading the message
     * @return true if newly marked as read, false if already read
     */
    @Transactional
    public boolean markMessageAsRead(Long messageId, String userId) {
        // Check if already read (avoid duplicate insert)
        if (readReceiptRepository.existsByMessageIdAndUserId(messageId, userId)) {
            log.debug("[ReadReceipt] Message {} already read by user {}", messageId, userId);
            return false;
        }

        MessageReadReceipt receipt = MessageReadReceipt.create(messageId, userId);
        readReceiptRepository.save(receipt);

        log.debug("[ReadReceipt] Message {} marked as read by user {}", messageId, userId);
        return true;
    }

    /**
     * Get unread message count for a user in a conversation.
     * 
     * Single query instead of loading all messages and filtering.
     * 
     * @param conversationId The conversation ID
     * @param userId The user ID
     * @return Count of unread messages
     */
    @Transactional(readOnly = true)
    public long getUnreadCount(Long conversationId, String userId) {
        return readReceiptRepository.countUnreadMessagesInConversation(conversationId, userId);
    }

    /**
     * Get all users who have read a message.
     * 
     * @param messageId The message ID
     * @return Set of user IDs who read the message
     */
    @Transactional(readOnly = true)
    public Set<String> getReadByUsers(Long messageId) {
        return readReceiptRepository.findUserIdsWhoReadMessage(messageId);
    }

    /**
     * Check if a user has read a specific message.
     * 
     * @param messageId The message ID
     * @param userId The user ID
     * @return true if the user has read the message
     */
    @Transactional(readOnly = true)
    public boolean hasUserReadMessage(Long messageId, String userId) {
        return readReceiptRepository.existsByMessageIdAndUserId(messageId, userId);
    }

    /**
     * Get all read receipts for a message.
     * Used for detailed "seen by" list with timestamps.
     * 
     * @param messageId The message ID
     * @return List of read receipts with timestamps
     */
    @Transactional(readOnly = true)
    public List<MessageReadReceipt> getReadReceipts(Long messageId) {
        return readReceiptRepository.findByMessageId(messageId);
    }
}
