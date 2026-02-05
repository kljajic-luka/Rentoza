package org.example.rentoza.exception;

/**
 * Exception thrown when input validation fails.
 * 
 * Use Cases:
 * - Invalid date ranges (start date after end date)
 * - Trip start time too close to current time (< 1 hour buffer)
 * - Age requirement violations
 * - Missing required fields
 * - Business rule violations (min/max rental days)
 * 
 * HTTP Status: 400 Bad Request
 * 
 * Security:
 * - Message may be shown to users, so it should be user-friendly
 * - Do NOT include PII in error messages
 * - Use generic messages for security-sensitive validations
 */
public class ValidationException extends RuntimeException {
    
    public ValidationException(String message) {
        super(message);
    }
    
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
