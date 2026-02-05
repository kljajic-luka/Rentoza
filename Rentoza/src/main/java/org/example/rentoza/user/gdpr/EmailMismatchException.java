package org.example.rentoza.user.gdpr;

/**
 * Exception thrown when email confirmation doesn't match.
 */
public class EmailMismatchException extends RuntimeException {
    public EmailMismatchException(String message) {
        super(message);
    }
}
