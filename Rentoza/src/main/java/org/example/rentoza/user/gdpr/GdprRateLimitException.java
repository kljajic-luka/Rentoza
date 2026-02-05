package org.example.rentoza.user.gdpr;

import lombok.Getter;
import java.time.LocalDateTime;

/**
 * Exception thrown when GDPR data export rate limit is exceeded.
 */
@Getter
public class GdprRateLimitException extends RuntimeException {
    private final LocalDateTime retryAfter;
    
    public GdprRateLimitException(String message, LocalDateTime retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }
}
