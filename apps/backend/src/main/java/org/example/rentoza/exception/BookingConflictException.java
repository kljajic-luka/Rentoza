package org.example.rentoza.exception;

/**
 * Exception thrown when a booking request conflicts with existing bookings or system state.
 * 
 * Use Cases:
 * - Concurrent booking approval attempts (optimistic locking failure)
 * - Date conflicts (car already booked for requested period)
 * - Invalid state transitions (e.g., approving already-declined booking)
 * - Race conditions in booking approval workflow
 * 
 * HTTP Status: 409 Conflict
 * 
 * Security:
 * - Message should NOT contain PII
 * - Use booking IDs and dates only (no user names/emails)
 */
public class BookingConflictException extends RuntimeException {
    
    public BookingConflictException(String message) {
        super(message);
    }
    
    public BookingConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
