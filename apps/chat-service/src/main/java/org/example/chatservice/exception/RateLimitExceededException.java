package org.example.chatservice.exception;

/**
 * Exception thrown when a user exceeds their rate limit.
 */
public class RateLimitExceededException extends RuntimeException {
    
    private final long retryAfterSeconds;

    public RateLimitExceededException(String message) {
        super(message);
        this.retryAfterSeconds = 60; // Default: retry after 1 minute
    }

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
