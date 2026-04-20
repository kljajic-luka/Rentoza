package org.example.rentoza.security.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rate limiting service using fixed-window counter algorithm.
 * 
 * FALLBACK IMPLEMENTATION:
 * - Used when Redis is not configured (spring.data.redis.host not set)
 * - Suitable for single-instance deployments or development
 * - For production multi-instance deployments, configure Redis
 * 
 * Algorithm: Fixed-Window Counter
 * - Each key (IP or user) has a counter that resets after the window expires
 * - NOT a sliding window: a burst at a window boundary can allow up to 2x the limit
 * - Bucket refills automatically after window expiration
 * - Atomic operations ensure thread safety
 * 
 * Limitations:
 * - Not suitable for horizontal scaling (each instance has separate state)
 * - State lost on application restart
 * 
 * Security:
 * - Thread-safe atomic operations (no race conditions)
 * - Automatic expiration prevents memory leaks
 * - No persistent storage (stateless across restarts)
 * 
 * @author Rentoza Security Team
 * @since Phase 1 - Initial Implementation
 */
@Service
@ConditionalOnMissingBean(RedisRateLimitService.class)
public class InMemoryRateLimitService implements RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRateLimitService.class);

    // Thread-safe in-memory storage: key -> BucketState
    private final Map<String, BucketState> buckets = new ConcurrentHashMap<>();

    /**
     * Checks if a request should be allowed based on rate limit.
     * 
     * @param key Unique identifier (IP address or JWT email)
     * @param limit Maximum requests allowed in window
     * @param windowSeconds Time window in seconds
     * @return true if request is allowed, false if limit exceeded
     */
    @Override
    public boolean allowRequest(String key, int limit, int windowSeconds) {
        Instant now = Instant.now();
        
        // Get or create bucket for this key
        BucketState bucket = buckets.compute(key, (k, existing) -> {
            if (existing == null || existing.windowEnd.isBefore(now)) {
                // Create new bucket or reset expired bucket
                return new BucketState(1, now.plusSeconds(windowSeconds));
            } else {
                // Increment counter within current window
                existing.count++;
                return existing;
            }
        });

        boolean allowed = bucket.count <= limit;

        if (!allowed) {
            log.warn("⚠️ Rate limit exceeded: key={}, count={}, limit={}, window={}s", 
                    key, bucket.count, limit, windowSeconds);
        }

        // Clean up expired buckets periodically (simple memory management)
        if (buckets.size() > 10000) {
            cleanupExpiredBuckets(now);
        }

        return allowed;
    }

    /**
     * Get current request count for a key (for monitoring/testing)
     */
    @Override
    public int getCurrentCount(String key) {
        BucketState bucket = buckets.get(key);
        if (bucket == null || bucket.windowEnd.isBefore(Instant.now())) {
            return 0;
        }
        return bucket.count;
    }

    /**
     * Get remaining seconds in current window (for Retry-After header)
     */
    @Override
    public long getRemainingSeconds(String key) {
        BucketState bucket = buckets.get(key);
        if (bucket == null) {
            return 0;
        }
        long remaining = Duration.between(Instant.now(), bucket.windowEnd).getSeconds();
        return Math.max(0, remaining);
    }

    /**
     * Clear all rate limit state (for testing)
     */
    @Override
    public void reset() {
        buckets.clear();
        log.debug("🔄 Rate limit state reset");
    }

    /**
     * Remove expired buckets to prevent memory leaks
     */
    private void cleanupExpiredBuckets(Instant now) {
        buckets.entrySet().removeIf(entry -> entry.getValue().windowEnd.isBefore(now));
        log.debug("🧹 Cleaned up expired rate limit buckets: {} remaining", buckets.size());
    }

    /**
     * Bucket state for a single key (IP or user)
     */
    private static class BucketState {
        int count;              // Current request count
        Instant windowEnd;      // When this window expires

        BucketState(int count, Instant windowEnd) {
            this.count = count;
            this.windowEnd = windowEnd;
        }
    }
}
