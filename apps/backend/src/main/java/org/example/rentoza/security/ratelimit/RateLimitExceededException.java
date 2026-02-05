package org.example.rentoza.security.ratelimit;

/**
 * Exception thrown when a rate limit is exceeded.
 * 
 * Returns HTTP 429 (Too Many Requests) with retry-after metadata.
 * 
 * Security:
 * - Minimal error disclosure (no internal implementation details)
 * - Includes retry-after hint for client backoff strategies
 * - Logs rate limit violations for monitoring
 * 
 * Usage:
 * throw new RateLimitExceededException("Rate limit exceeded for /api/auth/login", 60);
 */
public class RateLimitExceededException extends RuntimeException {

    private final String endpoint;
    private final int retryAfterSeconds;

    public RateLimitExceededException(String message, String endpoint, int retryAfterSeconds) {
        super(message);
        this.endpoint = endpoint;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
