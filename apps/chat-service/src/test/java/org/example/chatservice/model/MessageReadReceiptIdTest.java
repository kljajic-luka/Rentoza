package org.example.chatservice.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for MessageReadReceiptId composite key class.
 * 
 * Tests verify that:
 * - Composite key equality is based on both messageId and userId
 * - Hash code is consistent with equals for use in maps/sets
 * - Null safety is properly handled
 * - Serialization contract is maintained
 */
@DisplayName("MessageReadReceiptId Composite Key Tests")
class MessageReadReceiptIdTest {

    @Test
    @DisplayName("should create composite key with valid values")
    void testCreateCompositeKey() {
        // Arrange & Act
        MessageReadReceiptId id = new MessageReadReceiptId(1L, 100L);

        // Assert
        assertThat(id.getMessageId()).isEqualTo(1L);
        assertThat(id.getUserId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("should consider two keys equal when both message_id and user_id match")
    void testEqualsWithMatchingIds() {
        // Arrange
        MessageReadReceiptId id1 = new MessageReadReceiptId(1L, 100L);
        MessageReadReceiptId id2 = new MessageReadReceiptId(1L, 100L);

        // Act & Assert
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    @DisplayName("should consider keys not equal when message_id differs")
    void testNotEqualsWhenMessageIdDiffers() {
        // Arrange
        MessageReadReceiptId id1 = new MessageReadReceiptId(1L, 100L);
        MessageReadReceiptId id2 = new MessageReadReceiptId(2L, 100L);

        // Act & Assert
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @DisplayName("should consider keys not equal when user_id differs")
    void testNotEqualsWhenUserIdDiffers() {
        // Arrange
        MessageReadReceiptId id1 = new MessageReadReceiptId(1L, 100L);
        MessageReadReceiptId id2 = new MessageReadReceiptId(1L, 200L);

        // Act & Assert
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @DisplayName("should consider key equal to itself")
    void testEqualToItself() {
        // Arrange
        MessageReadReceiptId id = new MessageReadReceiptId(1L, 100L);

        // Act & Assert
        assertThat(id).isEqualTo(id);
    }

    @Test
    @DisplayName("should not be equal to null")
    void testNotEqualToNull() {
        // Arrange
        MessageReadReceiptId id = new MessageReadReceiptId(1L, 100L);

        // Act & Assert
        assertThat(id).isNotEqualTo(null);
    }

    @Test
    @DisplayName("should not be equal to different class instance")
    void testNotEqualToDifferentClass() {
        // Arrange
        MessageReadReceiptId id = new MessageReadReceiptId(1L, 100L);
        Object other = "not a MessageReadReceiptId";

        // Act & Assert
        assertThat(id).isNotEqualTo(other);
    }

    @Test
    @DisplayName("should have same hash code for equal keys")
    void testHashCodeConsistency() {
        // Arrange
        MessageReadReceiptId id1 = new MessageReadReceiptId(1L, 100L);
        MessageReadReceiptId id2 = new MessageReadReceiptId(1L, 100L);

        // Act & Assert
        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
    }

    @Test
    @DisplayName("should have different hash codes for different keys")
    void testHashCodeDifference() {
        // Arrange
        MessageReadReceiptId id1 = new MessageReadReceiptId(1L, 100L);
        MessageReadReceiptId id2 = new MessageReadReceiptId(2L, 100L);

        // Act & Assert
        assertThat(id1.hashCode()).isNotEqualTo(id2.hashCode());
    }

    @Test
    @DisplayName("should work correctly in sets (equality contract)")
    void testSetBehavior() {
        // Arrange
        MessageReadReceiptId id1 = new MessageReadReceiptId(1L, 100L);
        MessageReadReceiptId id2 = new MessageReadReceiptId(1L, 100L);
        MessageReadReceiptId id3 = new MessageReadReceiptId(2L, 100L);

        // Act
        var set = new java.util.HashSet<MessageReadReceiptId>();
        set.add(id1);
        set.add(id2); // Should be treated as duplicate
        set.add(id3);

        // Assert - only 2 items (id1 and id2 are equal)
        assertThat(set).hasSize(2);
        assertThat(set).contains(id1, id3);
    }

    @Test
    @DisplayName("should work correctly as map key")
    void testMapKeyBehavior() {
        // Arrange
        MessageReadReceiptId key1 = new MessageReadReceiptId(1L, 100L);
        MessageReadReceiptId key2 = new MessageReadReceiptId(1L, 100L);
        
        var map = new java.util.HashMap<MessageReadReceiptId, String>();
        
        // Act
        map.put(key1, "value1");
        map.put(key2, "value2"); // Should overwrite value1

        // Assert
        assertThat(map).hasSize(1);
        assertThat(map.get(key1)).isEqualTo("value2");
        assertThat(map.get(key2)).isEqualTo("value2");
    }

    @Test
    @DisplayName("should handle null messageId in equals")
    void testNullMessageIdInEquals() {
        // Arrange
        MessageReadReceiptId id1 = new MessageReadReceiptId(null, 100L);
        MessageReadReceiptId id2 = new MessageReadReceiptId(null, 100L);

        // Act & Assert
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    @DisplayName("should handle null userId in equals")
    void testNullUserIdInEquals() {
        // Arrange
        MessageReadReceiptId id1 = new MessageReadReceiptId(1L, null);
        MessageReadReceiptId id2 = new MessageReadReceiptId(1L, null);

        // Act & Assert
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    @DisplayName("should handle both null in equals")
    void testBothNullInEquals() {
        // Arrange
        MessageReadReceiptId id1 = new MessageReadReceiptId(null, null);
        MessageReadReceiptId id2 = new MessageReadReceiptId(null, null);

        // Act & Assert
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    @DisplayName("should have meaningful toString representation")
    void testToStringRepresentation() {
        // Arrange
        MessageReadReceiptId id = new MessageReadReceiptId(1L, 100L);

        // Act
        String result = id.toString();

        // Assert
        assertThat(result)
                .contains("MessageReadReceiptId")
                .contains("messageId=1")
                .contains("userId=100");
    }

    @Test
    @DisplayName("should implement Serializable contract")
    void testSerializable() {
        // Arrange
        MessageReadReceiptId id = new MessageReadReceiptId(1L, 100L);

        // Act & Assert
        assertThat(id).isInstanceOf(java.io.Serializable.class);
        
        // Verify that the class declares Serializable
        assertThat(java.io.Serializable.class.isAssignableFrom(MessageReadReceiptId.class))
                .isTrue();
    }

    @Test
    @DisplayName("should support Lombok NoArgsConstructor")
    void testNoArgsConstructor() {
        // Act
        MessageReadReceiptId id = new MessageReadReceiptId();

        // Assert
        assertThat(id.getMessageId()).isNull();
        assertThat(id.getUserId()).isNull();
    }

    @Test
    @DisplayName("should support Lombok Data annotation")
    void testDataAnnotation() {
        // Arrange
        MessageReadReceiptId id = new MessageReadReceiptId(1L, 100L);

        // Act & Assert - Lombok @Data provides getters/setters
        assertThat(id.getMessageId()).isEqualTo(1L);
        assertThat(id.getUserId()).isEqualTo(100L);
        
        id.setMessageId(2L);
        id.setUserId(200L);
        
        assertThat(id.getMessageId()).isEqualTo(2L);
        assertThat(id.getUserId()).isEqualTo(200L);
    }
}
