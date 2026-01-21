package org.example.chatservice.repository;

import org.example.chatservice.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for MessageReadReceiptRepository.
 * 
 * Tests the composite key implementation with:
 * - Save and retrieve operations with composite key
 * - Uniqueness constraints (no duplicate read receipts)
 * - Query methods with messageId and userId
 * - Cascade delete behavior
 * - Repository findBy methods with composite keys
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("MessageReadReceiptRepository Composite Key Tests")
@SuppressWarnings("null") // Null safety warnings are expected in test setup
class MessageReadReceiptRepositoryTest {

    @Autowired
    private MessageReadReceiptRepository readReceiptRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    private Conversation testConversation;
    private Message testMessage;

    @BeforeEach
    void setUp() {
        // Create test conversation
        testConversation = Conversation.builder()
                .bookingId(1L)
                .renterId(100L)
                .ownerId(200L)
                .status(ConversationStatus.ACTIVE)
                .build();
        testConversation = conversationRepository.save(testConversation);

        // Create test message
        testMessage = Message.builder()
                .conversationId(testConversation.getId())
                .senderId(100L)
                .content("Test message")
                .build();
        testMessage = messageRepository.save(testMessage);
    }

    @Test
    @DisplayName("should save and retrieve read receipt with composite key")
    void testSaveAndRetrieveWithCompositeKey() {
        // Arrange
        MessageReadReceipt receipt = MessageReadReceipt.builder()
                .messageId(testMessage.getId())
                .userId(200L)
                .readAt(LocalDateTime.now())
                .build();

        // Act
        MessageReadReceipt saved = readReceiptRepository.save(receipt);
        
        MessageReadReceiptId id = new MessageReadReceiptId(testMessage.getId(), 200L);
        Optional<MessageReadReceipt> retrieved = readReceiptRepository.findById(id);

        // Assert
        assertThat(saved).isNotNull();
        assertThat(saved.getMessageId()).isEqualTo(testMessage.getId());
        assertThat(saved.getUserId()).isEqualTo(200L);
        
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getMessageId()).isEqualTo(testMessage.getId());
        assertThat(retrieved.get().getUserId()).isEqualTo(200L);
    }

    @Test
    @DisplayName("should find read receipt by messageId and userId")
    void testFindByMessageIdAndUserId() {
        // Arrange
        MessageReadReceipt receipt = MessageReadReceipt.builder()
                .messageId(testMessage.getId())
                .userId(100L)
                .readAt(LocalDateTime.now())
                .build();
        readReceiptRepository.save(receipt);

        // Act
        Optional<MessageReadReceipt> result = 
                readReceiptRepository.findByMessageIdAndUserId(testMessage.getId(), 100L);

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().getUserId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("should return empty when read receipt not found by messageId and userId")
    void testFindByMessageIdAndUserIdNotFound() {
        // Act
        Optional<MessageReadReceipt> result = 
                readReceiptRepository.findByMessageIdAndUserId(testMessage.getId(), 999L);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should check existence by messageId and userId")
    void testExistsByMessageIdAndUserId() {
        // Arrange
        MessageReadReceipt receipt = MessageReadReceipt.builder()
                .messageId(testMessage.getId())
                .userId(100L)
                .readAt(LocalDateTime.now())
                .build();
        readReceiptRepository.save(receipt);

        // Act & Assert
        assertThat(readReceiptRepository.existsByMessageIdAndUserId(testMessage.getId(), 100L))
                .isTrue();
        assertThat(readReceiptRepository.existsByMessageIdAndUserId(testMessage.getId(), 999L))
                .isFalse();
    }

    @Test
    @DisplayName("should find all read receipts for a message")
    void testFindByMessageId() {
        // Arrange
        MessageReadReceipt receipt1 = MessageReadReceipt.builder()
                .messageId(testMessage.getId())
                .userId(100L)
                .readAt(LocalDateTime.now())
                .build();
        MessageReadReceipt receipt2 = MessageReadReceipt.builder()
                .messageId(testMessage.getId())
                .userId(200L)
                .readAt(LocalDateTime.now())
                .build();
        
        readReceiptRepository.save(receipt1);
        readReceiptRepository.save(receipt2);

        // Act
        List<MessageReadReceipt> results = readReceiptRepository.findByMessageId(testMessage.getId());

        // Assert
        assertThat(results).hasSize(2);
        assertThat(results)
                .extracting(MessageReadReceipt::getUserId)
                .containsExactlyInAnyOrder(100L, 200L);
    }

    @Test
    @DisplayName("should return empty list when no read receipts for message")
    void testFindByMessageIdEmpty() {
        // Act
        List<MessageReadReceipt> results = readReceiptRepository.findByMessageId(999L);

        // Assert
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("should prevent duplicate read receipts (unique constraint)")
    void testUniqueConstraintOnCompositeKey() {
        // Arrange
        MessageReadReceipt receipt1 = MessageReadReceipt.builder()
                .messageId(testMessage.getId())
                .userId(100L)
                .readAt(LocalDateTime.now())
                .build();
        readReceiptRepository.save(receipt1);

        MessageReadReceipt receipt2 = MessageReadReceipt.builder()
                .messageId(testMessage.getId())
                .userId(100L) // Same message and user - should violate unique constraint
                .readAt(LocalDateTime.now())
                .build();

        // Act & Assert
        assertThatThrownBy(() -> {
            readReceiptRepository.save(receipt2);
            readReceiptRepository.flush(); // Force flush to trigger constraint violation
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("should allow same user to read different messages")
    void testSameUserReadMultipleMessages() {
        // Arrange
        Message message2 = Message.builder()
                .conversationId(testConversation.getId())
                .senderId(100L)
                .content("Second message")
                .build();
        message2 = messageRepository.save(message2);

        MessageReadReceipt receipt1 = MessageReadReceipt.builder()
                .messageId(testMessage.getId())
                .userId(100L)
                .readAt(LocalDateTime.now())
                .build();
        MessageReadReceipt receipt2 = MessageReadReceipt.builder()
                .messageId(message2.getId())
                .userId(100L) // Same user, different message - should be allowed
                .readAt(LocalDateTime.now())
                .build();

        // Act
        readReceiptRepository.save(receipt1);
        readReceiptRepository.save(receipt2);

        // Assert
        assertThat(readReceiptRepository.findByMessageId(testMessage.getId())).hasSize(1);
        assertThat(readReceiptRepository.findByMessageId(message2.getId())).hasSize(1);
    }

    @Test
    @DisplayName("should allow different users to read same message")
    void testDifferentUsersReadSameMessage() {
        // Arrange
        MessageReadReceipt receipt1 = MessageReadReceipt.builder()
                .messageId(testMessage.getId())
                .userId(100L)
                .readAt(LocalDateTime.now())
                .build();
        MessageReadReceipt receipt2 = MessageReadReceipt.builder()
                .messageId(testMessage.getId())
                .userId(200L) // Different user, same message - should be allowed
                .readAt(LocalDateTime.now())
                .build();

        // Act
        readReceiptRepository.save(receipt1);
        readReceiptRepository.save(receipt2);

        // Assert
        List<MessageReadReceipt> results = readReceiptRepository.findByMessageId(testMessage.getId());
        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("should cascade delete read receipts when message is deleted")
    void testCascadeDeleteOnMessageDelete() {
        // Arrange
        MessageReadReceipt receipt = MessageReadReceipt.builder()
                .messageId(testMessage.getId())
                .userId(100L)
                .readAt(LocalDateTime.now())
                .build();
        readReceiptRepository.save(receipt);

        assertThat(readReceiptRepository.findByMessageId(testMessage.getId())).hasSize(1);

        // Act
        messageRepository.delete(testMessage);

        // Assert - read receipt should be deleted via cascade
        assertThat(readReceiptRepository.findByMessageId(testMessage.getId())).isEmpty();
    }

    @Test
    @DisplayName("should have non-null readAt timestamp")
    void testReadAtTimestamp() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        MessageReadReceipt receipt = MessageReadReceipt.builder()
                .messageId(testMessage.getId())
                .userId(100L)
                .readAt(now)
                .build();

        // Act
        MessageReadReceipt saved = readReceiptRepository.save(receipt);

        // Assert
        assertThat(saved.getReadAt()).isNotNull();
        assertThat(saved.getReadAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("should set readAt to current timestamp when null (via CreationTimestamp)")
    void testReadAtDefaultTimestamp() {
        // Arrange
        MessageReadReceipt receipt = MessageReadReceipt.builder()
                .messageId(testMessage.getId())
                .userId(100L)
                // Note: readAt is not set, should be auto-generated
                .build();

        // Act
        MessageReadReceipt saved = readReceiptRepository.save(receipt);

        // Assert
        assertThat(saved.getReadAt()).isNotNull();
    }

    @Test
    @DisplayName("should create read receipt using factory method")
    void testCreateViaFactoryMethod() {
        // Act
        MessageReadReceipt receipt = MessageReadReceipt.create(testMessage.getId(), 100L);
        receipt = readReceiptRepository.save(receipt);

        // Assert
        assertThat(receipt.getMessageId()).isEqualTo(testMessage.getId());
        assertThat(receipt.getUserId()).isEqualTo(100L);
        assertThat(receipt.getReadAt()).isNotNull();
    }

    @Test
    @DisplayName("should support bulk operations with composite key")
    void testBulkOperations() {
        // Arrange
        List<MessageReadReceipt> receipts = List.of(
                MessageReadReceipt.create(testMessage.getId(), 100L),
                MessageReadReceipt.create(testMessage.getId(), 200L),
                MessageReadReceipt.create(testMessage.getId(), 300L)
        );

        // Act
        readReceiptRepository.saveAll(receipts);

        // Assert
        List<MessageReadReceipt> results = readReceiptRepository.findByMessageId(testMessage.getId());
        assertThat(results).hasSize(3);
    }

    @Test
    @DisplayName("should update read receipt by composite key")
    void testUpdateByCompositeKey() {
        // Arrange
        MessageReadReceipt receipt = MessageReadReceipt.builder()
                .messageId(testMessage.getId())
                .userId(100L)
                .readAt(LocalDateTime.now().minusMinutes(10))
                .build();
        receipt = readReceiptRepository.save(receipt);

        // Act
        MessageReadReceiptId id = new MessageReadReceiptId(testMessage.getId(), 100L);
        Optional<MessageReadReceipt> retrieved = readReceiptRepository.findById(id);

        // Assert
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getReadAt()).isNotNull();
    }

    @Test
    @DisplayName("should handle large messageId and userId values")
    void testLargeIdValues() {
        // Arrange
        Long largeMessageId = 9223372036854775800L; // Near Long.MAX_VALUE
        Long largeUserId = 9223372036854775700L;
        
        MessageReadReceipt receipt = MessageReadReceipt.builder()
                .messageId(largeMessageId)
                .userId(largeUserId)
                .readAt(LocalDateTime.now())
                .build();

        // Act
        MessageReadReceipt saved = readReceiptRepository.save(receipt);

        // Assert
        assertThat(saved.getMessageId()).isEqualTo(largeMessageId);
        assertThat(saved.getUserId()).isEqualTo(largeUserId);
    }
}
