package org.example.chatservice.exception;

/**
 * Thrown when Supabase Storage returns an unexpected 5xx response or is unreachable.
 *
 * Mapped to HTTP 502 Bad Gateway in {@link GlobalExceptionHandler} so the caller
 * receives a safe, user-friendly error message rather than raw upstream details.
 */
public class StorageUpstreamException extends RuntimeException {

    public StorageUpstreamException(String message) {
        super(message);
    }

    public StorageUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
