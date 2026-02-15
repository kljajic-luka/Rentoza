package org.example.rentoza.exception;

/**
 * Thrown when host approval is attempted after the booking decision deadline.
 *
 * Intended to map to HTTP 409 while allowing the preceding EXPIRED_SYSTEM state
 * transition to remain committed.
 */
public class ApprovalDecisionDeadlineExceededException extends BookingConflictException {

    public ApprovalDecisionDeadlineExceededException(String message) {
        super(message);
    }
}
