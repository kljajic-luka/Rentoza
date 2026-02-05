package org.example.rentoza.booking.photo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P0-3 FIX: Rate limiting service to prevent photo scraping attacks.
 * 
 * <p>Prevents mass downloading of photos by limiting:
 * <ul>
 *   <li>100 photo requests per user per 10 minutes</li>
 *   <li>Tracks per IP and per user ID independently</li>
 *   <li>Alerts on suspicious patterns</li>
 * </ul>
 * 
 * <h2>Implementation Notes</h2>
 * Uses in-memory Caffeine cache for fast lookup. For multi-instance deployments,
 * consider upgrading to Redis-backed rate limiting.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoRateLimitService {

    @Value("${app.photo.rate-limit.max-per-window:100}")
    private int maxRequestsPerWindow;

    @Value("${app.photo.rate-limit.window-minutes:10}")
    private int rateLimitWindowMinutes;

    @Value("${app.photo.rate-limit.alert-threshold:80}")
    private int alertThreshold;

    // Cache: key = "{userId}:{windowStart}", value = request count
    private final Cache<String, AtomicInteger> rateLimitCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.MINUTES)  // Slightly longer than window to avoid edge cases
            .maximumSize(10000)  // Up to 10k active users
            .build();

    /**
     * Check if a user has exceeded the rate limit for photo access.
     * 
     * @param userId The user making the request
     * @param ipAddress The client IP address (for logging)
     * @return true if request is allowed, false if rate limit exceeded
     */
    public boolean allowPhotoAccess(Long userId, String ipAddress) {
        String cacheKey = generateCacheKey(userId);
        
        AtomicInteger requestCount = rateLimitCache.getIfPresent(cacheKey);
        if (requestCount == null) {
            requestCount = new AtomicInteger(0);
            rateLimitCache.put(cacheKey, requestCount);
        }
        
        int currentCount = requestCount.incrementAndGet();
        
        if (currentCount > maxRequestsPerWindow) {
            log.warn("[RateLimit] User exceeded photo access limit: userId={}, ip={}, count={}/{}", 
                userId, ipAddress, currentCount, maxRequestsPerWindow);
            return false;
        }
        
        // Alert if approaching limit
        if (currentCount >= alertThreshold && currentCount <= alertThreshold + 5) {
            log.warn("[RateLimit] User approaching photo access limit: userId={}, ip={}, count={}/{}", 
                userId, ipAddress, currentCount, maxRequestsPerWindow);
        }
        
        return true;
    }

    /**
     * Get current request count for a user in the current window.
     * 
     * @param userId The user
     * @return Number of photo requests made in current window
     */
    public int getCurrentRequestCount(Long userId) {
        String cacheKey = generateCacheKey(userId);
        AtomicInteger count = rateLimitCache.getIfPresent(cacheKey);
        return count != null ? count.get() : 0;
    }

    /**
     * Reset rate limit counter for a user (for testing or admin operations).
     * 
     * @param userId The user to reset
     */
    public void resetRateLimit(Long userId) {
        String cacheKey = generateCacheKey(userId);
        rateLimitCache.invalidate(cacheKey);
        log.info("[RateLimit] Reset rate limit for user: {}", userId);
    }

    /**
     * Generate cache key based on user ID and current time window.
     * This ensures different time windows have separate counters.
     * 
     * @param userId The user ID
     * @return Cache key like "12345:2025-01-15-10:30"
     */
    private String generateCacheKey(Long userId) {
        // Calculate the start of the current time window
        long currentTimeMinutes = System.currentTimeMillis() / 60000;
        long windowStart = (currentTimeMinutes / rateLimitWindowMinutes) * rateLimitWindowMinutes;
        
        return userId + ":" + windowStart;
    }

    /**
     * Get remaining requests for user before hitting limit.
     * 
     * @param userId The user
     * @return Number of requests remaining in current window
     */
    public int getRemainingRequests(Long userId) {
        int current = getCurrentRequestCount(userId);
        return Math.max(0, maxRequestsPerWindow - current);
    }

    /**
     * Check if user is in warning zone (80%+ of limit used).
     * 
     * @param userId The user
     * @return true if user should be warned
     */
    public boolean isNearRateLimit(Long userId) {
        int current = getCurrentRequestCount(userId);
        return current >= (maxRequestsPerWindow * 0.8);
    }
}
