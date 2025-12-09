package org.example.chatservice.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter configuration for chat messaging.
 * 
 * Protects against:
 * - Spam attacks (excessive message sending)
 * - DDoS via message flooding
 * - Bot-driven abuse
 * 
 * Limits:
 * - 50 messages per hour per user (sustained)
 * - 5 messages per minute burst (prevents flooding)
 */
@Component
@Slf4j
public class RateLimitConfig {

    // Per-user bucket storage (in-memory for single instance, Redis for distributed)
    private final ConcurrentHashMap<String, Bucket> userBuckets = new ConcurrentHashMap<>();

    // Rate limit configuration
    private static final int HOURLY_LIMIT = 50;
    private static final int BURST_LIMIT = 5;
    private static final Duration BURST_DURATION = Duration.ofMinutes(1);
    private static final Duration HOURLY_DURATION = Duration.ofHours(1);

    /**
     * Get or create a rate limit bucket for a user.
     * 
     * @param userId The user's ID
     * @return The rate limit bucket for this user
     */
    public Bucket resolveBucket(String userId) {
        return userBuckets.computeIfAbsent(userId, this::createNewBucket);
    }

    /**
     * Create a new rate limit bucket with default limits.
     */
    private Bucket createNewBucket(String userId) {
        // Hourly limit: 50 messages refilled completely every hour
        Bandwidth hourlyLimit = Bandwidth.builder()
                .capacity(HOURLY_LIMIT)
                .refillIntervally(HOURLY_LIMIT, HOURLY_DURATION)
                .build();

        // Burst limit: 5 messages per minute to prevent rapid-fire spam
        Bandwidth burstLimit = Bandwidth.builder()
                .capacity(BURST_LIMIT)
                .refillIntervally(BURST_LIMIT, BURST_DURATION)
                .build();

        log.debug("[RateLimit] Created new bucket for user: {}", userId);

        return Bucket.builder()
                .addLimit(hourlyLimit)
                .addLimit(burstLimit)
                .build();
    }

    /**
     * Try to consume a token from the user's bucket.
     * 
     * @param userId The user's ID
     * @return true if allowed, false if rate limited
     */
    public boolean tryConsume(String userId) {
        Bucket bucket = resolveBucket(userId);
        boolean allowed = bucket.tryConsume(1);

        if (!allowed) {
            log.warn("[RateLimit] Rate limit exceeded for user: {}", userId);
        }

        return allowed;
    }

    /**
     * Get remaining tokens for a user (for headers/debugging).
     */
    public long getRemainingTokens(String userId) {
        return resolveBucket(userId).getAvailableTokens();
    }

    /**
     * Clear all buckets (for testing).
     */
    public void clearAllBuckets() {
        userBuckets.clear();
    }

    /**
     * Get the hourly limit value.
     */
    public static int getHourlyLimit() {
        return HOURLY_LIMIT;
    }

    /**
     * Get the burst limit value.
     */
    public static int getBurstLimit() {
        return BURST_LIMIT;
    }
}
