package org.example.chatservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for MessageReadReceipt entity.
 * 
 * Represents the natural identifier of a read receipt:
 * - A user can read a message only once
 * - The combination of message_id and user_id uniquely identifies a read
 * receipt
 * 
 * This class is used with @IdClass annotation on MessageReadReceipt entity
 * and must implement Serializable for proper JPA handling.
 * 
 * Fields must:
 * - Match the @Id fields in the entity exactly
 * - Be in the same order
 * - Implement equals() and hashCode() correctly for composite key semantics
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageReadReceiptId implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The ID of the message being read.
     * Must match Message.id (BIGINT in database).
     */
    private Long messageId;

    /**
     * The ID of the user who read the message.
     * Must match users.id (BIGINT in database).
     */
    private Long userId;

    /**
     * Composite key equality.
     * Two read receipts are equal if they have the same message and user.
     * 
     * @param o Object to compare with
     * @return true if both message_id and user_id match
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MessageReadReceiptId that = (MessageReadReceiptId) o;
        return Objects.equals(messageId, that.messageId) &&
                Objects.equals(userId, that.userId);
    }

    /**
     * Hash code for composite key.
     * Required for use in maps/sets and database operations.
     * Combines both fields to ensure uniqueness.
     * 
     * @return Hash code based on messageId and userId
     */
    @Override
    public int hashCode() {
        return Objects.hash(messageId, userId);
    }

    /**
     * String representation for debugging.
     * 
     * @return String in format "MessageReadReceiptId(messageId=X, userId=Y)"
     */
    @Override
    public String toString() {
        return "MessageReadReceiptId(" +
                "messageId=" + messageId +
                ", userId=" + userId +
                ")";
    }
}
