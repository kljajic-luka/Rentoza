package org.example.chatservice.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for MessageReadReceipt entity.
 * 
 * Tests the composite key entity structure:
 * - Entity creation with composite key fields
 * - Factory method for convenient creation
 * - Relationship to Message entity
 * - Composite key field mapping
 */
@DisplayName("MessageReadReceipt Entity Tests")
class MessageReadReceiptTest {

    @Test
    @DisplayName("should create read receipt with composite key fields")
    void testCreateWithCompositeKey() {
        // Arrange & Act
        LocalDateTime now = LocalDateTime.now();
        MessageReadReceipt receipt = MessageReadReceipt.builder()
                .messageId(1L)
                .userId(100L)
                .readAt(now)
                .build();

        // Assert
        assertThat(receipt.getMessageId()).isEqualTo(1L);
        assertThat(receipt.getUserId()).isEqualTo(100L);
        assertThat(receipt.getReadAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("should create read receipt via factory method")
    void testCreateViaFactory() {
        // Act
        MessageReadReceipt receipt = MessageReadReceipt.create(1L, 100L);

        // Assert
        assertThat(receipt.getMessageId()).isEqualTo(1L);
        assertThat(receipt.getUserId()).isEqualTo(100L);
        assertThat(receipt.getReadAt()).isNull(); // Will be set by @CreationTimestamp on save
    }

    @Test
    @DisplayName("should have @IdClass annotation for composite key")
    void testHasIdClassAnnotation() {
        // Act
        Class<?> entityClass = MessageReadReceipt.class;
        
        // Assert
        assertThat(entityClass.isAnnotationPresent(jakarta.persistence.IdClass.class))
                .isTrue();
        
        jakarta.persistence.IdClass idClassAnnotation = 
                entityClass.getAnnotation(jakarta.persistence.IdClass.class);
        assertThat(idClassAnnotation.value()).isEqualTo(MessageReadReceiptId.class);
    }

    @Test
    @DisplayName("should have messageId and userId as @Id fields")
    void testIdFieldAnnotations() {
        // Act & Assert - Check messageId is @Id
        try {
            var messageIdField = MessageReadReceipt.class.getDeclaredField("messageId");
            assertThat(messageIdField.isAnnotationPresent(jakarta.persistence.Id.class))
                    .isTrue();
        } catch (NoSuchFieldException e) {
            fail("messageId field should exist");
        }

        // Act & Assert - Check userId is @Id
        try {
            var userIdField = MessageReadReceipt.class.getDeclaredField("userId");
            assertThat(userIdField.isAnnotationPresent(jakarta.persistence.Id.class))
                    .isTrue();
        } catch (NoSuchFieldException e) {
            fail("userId field should exist");
        }
    }

    @Test
    @DisplayName("should map to correct table")
    void testTableMapping() {
        // Act
        Class<?> entityClass = MessageReadReceipt.class;
        
        // Assert
        assertThat(entityClass.isAnnotationPresent(jakarta.persistence.Table.class))
                .isTrue();
        
        jakarta.persistence.Table tableAnnotation = 
                entityClass.getAnnotation(jakarta.persistence.Table.class);
        assertThat(tableAnnotation.name()).isEqualTo("message_read_by");
    }

    @Test
    @DisplayName("should have Column annotations with correct names")
    void testColumnAnnotations() {
        // Act & Assert - Check messageId column name
        try {
            var messageIdField = MessageReadReceipt.class.getDeclaredField("messageId");
            var columnAnnotation = messageIdField.getAnnotation(jakarta.persistence.Column.class);
            assertThat(columnAnnotation.name()).isEqualTo("message_id");
            assertThat(columnAnnotation.nullable()).isFalse();
        } catch (NoSuchFieldException e) {
            fail("messageId field should exist");
        }

        // Act & Assert - Check userId column name
        try {
            var userIdField = MessageReadReceipt.class.getDeclaredField("userId");
            var columnAnnotation = userIdField.getAnnotation(jakarta.persistence.Column.class);
            assertThat(columnAnnotation.name()).isEqualTo("user_id");
            assertThat(columnAnnotation.nullable()).isFalse();
        } catch (NoSuchFieldException e) {
            fail("userId field should exist");
        }
    }

    @Test
    @DisplayName("should have readAt with CreationTimestamp annotation")
    void testReadAtAnnotation() {
        // Act & Assert
        try {
            var readAtField = MessageReadReceipt.class.getDeclaredField("readAt");
            assertThat(readAtField.isAnnotationPresent(org.hibernate.annotations.CreationTimestamp.class))
                    .isTrue();
            assertThat(readAtField.isAnnotationPresent(jakarta.persistence.Column.class))
                    .isTrue();
            
            var columnAnnotation = readAtField.getAnnotation(jakarta.persistence.Column.class);
            assertThat(columnAnnotation.name()).isEqualTo("read_at");
            assertThat(columnAnnotation.nullable()).isFalse();
        } catch (NoSuchFieldException e) {
            fail("readAt field should exist");
        }
    }

    @Test
    @DisplayName("should have ManyToOne relationship to Message")
    void testMessageRelationship() {
        // Act & Assert
        try {
            var messageField = MessageReadReceipt.class.getDeclaredField("message");
            assertThat(messageField.isAnnotationPresent(jakarta.persistence.ManyToOne.class))
                    .isTrue();
            assertThat(messageField.isAnnotationPresent(jakarta.persistence.JoinColumn.class))
                    .isTrue();
            
            var manyToOneAnnotation = messageField.getAnnotation(jakarta.persistence.ManyToOne.class);
            assertThat(manyToOneAnnotation.fetch()).isEqualTo(jakarta.persistence.FetchType.LAZY);
            
            var joinColumnAnnotation = messageField.getAnnotation(jakarta.persistence.JoinColumn.class);
            assertThat(joinColumnAnnotation.name()).isEqualTo("message_id");
            assertThat(joinColumnAnnotation.insertable()).isFalse();
            assertThat(joinColumnAnnotation.updatable()).isFalse();
        } catch (NoSuchFieldException e) {
            fail("message field should exist");
        }
    }

    @Test
    @DisplayName("should use Lombok @Data annotation")
    void testLombokDataAnnotation() {
        // Act
        Class<?> entityClass = MessageReadReceipt.class;
        
        // Assert
        assertThat(entityClass.isAnnotationPresent(lombok.Data.class))
                .isTrue();
    }

    @Test
    @DisplayName("should use Lombok @Builder annotation")
    void testLombokBuilderAnnotation() {
        // Act
        Class<?> entityClass = MessageReadReceipt.class;
        
        // Assert
        assertThat(entityClass.isAnnotationPresent(lombok.Builder.class))
                .isTrue();
    }

    @Test
    @DisplayName("should support building with builder pattern")
    void testBuilderPattern() {
        // Act
        LocalDateTime now = LocalDateTime.now();
        MessageReadReceipt receipt = MessageReadReceipt.builder()
                .messageId(5L)
                .userId(50L)
                .readAt(now)
                .build();

        // Assert
        assertThat(receipt.getMessageId()).isEqualTo(5L);
        assertThat(receipt.getUserId()).isEqualTo(50L);
        assertThat(receipt.getReadAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("should support no-args constructor (via Lombok)")
    void testNoArgsConstructor() {
        // Act
        MessageReadReceipt receipt = new MessageReadReceipt();

        // Assert
        assertThat(receipt.getMessageId()).isNull();
        assertThat(receipt.getUserId()).isNull();
        assertThat(receipt.getReadAt()).isNull();
        assertThat(receipt.getMessage()).isNull();
    }

    @Test
    @DisplayName("should support all-args constructor (via Lombok)")
    void testAllArgsConstructor() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        Message mockMessage = new Message();

        // Act
        MessageReadReceipt receipt = new MessageReadReceipt(1L, 100L, now, mockMessage);

        // Assert
        assertThat(receipt.getMessageId()).isEqualTo(1L);
        assertThat(receipt.getUserId()).isEqualTo(100L);
        assertThat(receipt.getReadAt()).isEqualTo(now);
        assertThat(receipt.getMessage()).isEqualTo(mockMessage);
    }

    @Test
    @DisplayName("should support equality via Lombok @Data")
    void testLombokEquality() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        MessageReadReceipt receipt1 = MessageReadReceipt.builder()
                .messageId(1L)
                .userId(100L)
                .readAt(now)
                .build();
        MessageReadReceipt receipt2 = MessageReadReceipt.builder()
                .messageId(1L)
                .userId(100L)
                .readAt(now)
                .build();

        // Act & Assert - Note: @Data generates equals based on all fields
        assertThat(receipt1).isEqualTo(receipt2);
    }

    @Test
    @DisplayName("should generate meaningful toString via Lombok")
    void testLombokToString() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        MessageReadReceipt receipt = MessageReadReceipt.builder()
                .messageId(1L)
                .userId(100L)
                .readAt(now)
                .build();

        // Act
        String toString = receipt.toString();

        // Assert
        assertThat(toString)
                .contains("MessageReadReceipt")
                .contains("messageId=1")
                .contains("userId=100");
    }

    @Test
    @DisplayName("should generate consistent hashCode via Lombok")
    void testLombokHashCode() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        MessageReadReceipt receipt1 = MessageReadReceipt.builder()
                .messageId(1L)
                .userId(100L)
                .readAt(now)
                .build();
        MessageReadReceipt receipt2 = MessageReadReceipt.builder()
                .messageId(1L)
                .userId(100L)
                .readAt(now)
                .build();

        // Act & Assert
        assertThat(receipt1.hashCode()).isEqualTo(receipt2.hashCode());
    }

    @Test
    @DisplayName("should not have old id field from incorrect implementation")
    void testNoLegacyIdField() {
        // Act & Assert
        try {
            MessageReadReceipt.class.getDeclaredField("id");
            fail("Entity should not have an 'id' field - should use composite key");
        } catch (NoSuchFieldException e) {
            // Expected - field should not exist
            assertThat(e).isNotNull();
        }
    }

    @Test
    @DisplayName("should be marked as Entity")
    void testEntityAnnotation() {
        // Act
        Class<?> entityClass = MessageReadReceipt.class;
        
        // Assert
        assertThat(entityClass.isAnnotationPresent(jakarta.persistence.Entity.class))
                .isTrue();
    }

    @Test
    @DisplayName("should support setting message relationship")
    void testSetMessageRelationship() {
        // Arrange
        MessageReadReceipt receipt = MessageReadReceipt.create(1L, 100L);
        Message message = new Message();
        message.setId(1L);

        // Act
        receipt.setMessage(message);

        // Assert
        assertThat(receipt.getMessage()).isEqualTo(message);
        assertThat(receipt.getMessage().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("should handle null message relationship gracefully")
    void testNullMessageRelationship() {
        // Arrange & Act
        MessageReadReceipt receipt = MessageReadReceipt.create(1L, 100L);

        // Assert
        assertThat(receipt.getMessage()).isNull();
    }
}
