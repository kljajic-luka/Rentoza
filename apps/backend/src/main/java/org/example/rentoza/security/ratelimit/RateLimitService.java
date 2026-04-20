package org.example.rentoza.security.ratelimit;

/**
 * Interface for rate limiting service implementations.
 * 
 * Allows switching between:
 * - InMemoryRateLimitService: Single-instance deployments
 * - RedisRateLimitService: Distributed/multi-instance deployments
 * 
 * Design Decisions:
 * - Interface segregation: Service consumers don't need to know the implementation
 * - Testability: Easy to mock for unit tests
 * - Flexibility: Swap implementations via Spring profiles
 * 
 * @author Rentoza Security Team
 * @since Phase 2.3 - Redis Rate Limiting
 */
public interface RateLimitService {

    /**
     * Checks if a request should be allowed based on rate limit.
     *
     * @param key Unique identifier (IP address or JWT email)
     * @param limit Maximum requests allowed in window
     * @param windowSeconds Time window in seconds
     * @return true if request is allowed, false if limit exceeded
     */
    boolean allowRequest(String key, int limit, int windowSeconds);

    /**
     * Tier-aware rate limit check.
     *
     * <p>On infrastructure failure (e.g. Redis down):
     * <ul>
     *   <li>{@link RateLimitTier#CRITICAL} → returns {@code false} (fail-closed)</li>
     *   <li>{@link RateLimitTier#STANDARD} → returns {@code true}  (fail-open)</li>
     * </ul>
     *
     * <p>Default implementation delegates to {@link #allowRequest(String, int, int)}
     * (fail-open for all tiers), which is correct for the in-memory service that
     * has no external dependency to fail.
     *
     * @param key           Unique identifier (IP address or JWT email)
     * @param limit         Maximum requests allowed in window
     * @param windowSeconds Time window in seconds
     * @param tier          Criticality tier governing failure behavior
     * @return true if request is allowed, false if limit exceeded or fail-closed
     */
    default boolean allowRequest(String key, int limit, int windowSeconds, RateLimitTier tier) {
        return allowRequest(key, limit, windowSeconds);
    }

    /**
     * Get current request count for a key (for monitoring/testing)
     * 
     * @param key Unique identifier
     * @return Current request count in the active window
     */
    int getCurrentCount(String key);

    /**
     * Get remaining seconds in current window (for Retry-After header)
     * 
     * @param key Unique identifier
     * @return Seconds until window resets
     */
    long getRemainingSeconds(String key);

    /**
     * Clear all rate limit state (for testing)
     */
    void reset();
}
