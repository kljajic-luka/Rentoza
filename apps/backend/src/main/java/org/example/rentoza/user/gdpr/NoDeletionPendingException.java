package org.example.rentoza.user.gdpr;

/**
 * Exception thrown when trying to cancel a non-existent deletion request.
 */
public class NoDeletionPendingException extends RuntimeException {
    public NoDeletionPendingException(String message) {
        super(message);
    }
}
