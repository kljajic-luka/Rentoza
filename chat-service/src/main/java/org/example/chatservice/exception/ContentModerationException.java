package org.example.chatservice.exception;

/**
 * Exception thrown when message content violates content moderation policies.
 */
public class ContentModerationException extends RuntimeException {

    public ContentModerationException(String message) {
        super(message);
    }
}
