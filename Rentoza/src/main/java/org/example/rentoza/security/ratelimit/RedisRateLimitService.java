package org.example.rentoza.security.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Redis-backed rate limiting service using token bucket algorithm.
 * 
 * DISTRIBUTED DEPLOYMENTS:
 * - Stores rate limit counters in Redis instead of in-memory
 * - Ensures consistent rate limits across multiple application instances
 * - Uses atomic Lua scripts for thread-safe counter operations
 * 
 * Algorithm: Sliding Window Counter (simpler than token bucket for Redis)
 * - Each key (IP/user) has a counter with TTL = window duration
 * - Counter increments atomically using Redis INCR
 * - TTL auto-expiration prevents memory leaks
 * 
 * Fallback Strategy: FAIL OPEN
 * - If Redis is unavailable, requests are ALLOWED (not blocked)
 * - Logs ERROR for monitoring/alerting
 * - Rationale: Better UX than blocking all users during Redis outage
 * 
 * ACTIVATION:
 * - Requires app.redis.enabled=true in application properties
 * - Without this flag, InMemoryRateLimitService is used (development default)
 * 
 * Production Requirements:
 * - Redis 6.0+ recommended (for better memory management)
 * - Redis Sentinel or Cluster for HA
 * - Password authentication enabled
 * 
 * Configuration:
 * - app.redis.enabled=true: Enable Redis rate limiting
 * - app.rate-limit.redis-key-prefix: Namespace for rate limit keys
 * - spring.data.redis.*: Redis connection settings
 * 
 * @author Rentoza Security Team
 * @since Phase 2.3 - Redis Rate Limiting
 */
@Service
@Primary
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisRateLimitService implements RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitService.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final String keyPrefix;

    /**
     * Lua script for atomic increment with TTL.
     * 
     * ATOMICITY: This script runs as a single Redis operation.
     * Even under high concurrency, the counter will be accurate.
     * 
     * Logic:
     * 1. INCR the key (creates with value 1 if not exists)
     * 2. If this is a new key (count == 1), set TTL
     * 3. Return current count
     */
    private static final String INCREMENT_SCRIPT = 
            "local current = redis.call('INCR', KEYS[1]) " +
            "if current == 1 then " +
            "    redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
            "end " +
            "return current";

    private final DefaultRedisScript<Long> incrementScript;

    public RedisRateLimitService(
            RedisTemplate<String, String> redisTemplate,
            @Value("${app.rate-limit.redis-key-prefix:rate_limit}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;

        // Initialize Lua script
        this.incrementScript = new DefaultRedisScript<>();
        this.incrementScript.setScriptText(INCREMENT_SCRIPT);
        this.incrementScript.setResultType(Long.class);

        log.info("✅ RedisRateLimitService initialized with key prefix: {}", keyPrefix);
    }

    @Override
    public boolean allowRequest(String key, int limit, int windowSeconds) {
        String redisKey = buildRedisKey(key, windowSeconds);

        try {
            // Execute atomic increment with TTL
            Long currentCount = redisTemplate.execute(
                    incrementScript,
                    Collections.singletonList(redisKey),
                    String.valueOf(windowSeconds)
            );

            if (currentCount == null) {
                // Unexpected null response - fail open for UX
                log.error("⚠️ Redis returned null for rate limit key: {}. Failing OPEN.", redisKey);
                return true;
            }

            boolean allowed = currentCount <= limit;

            if (!allowed) {
                log.warn("🚫 Rate limit exceeded: key={}, count={}, limit={}, window={}s",
                        key, currentCount, limit, windowSeconds);
            }

            return allowed;

        } catch (Exception e) {
            // Redis unavailable - FAIL OPEN (allow request)
            log.error("❌ Redis error during rate limit check for key: {}. Failing OPEN. Error: {}",
                    redisKey, e.getMessage());
            return true;
        }
    }

    @Override
    public int getCurrentCount(String key) {
        // Try to get count from Redis (check multiple window sizes)
        for (int windowSeconds : List.of(60, 300, 3600)) {
            String redisKey = buildRedisKey(key, windowSeconds);
            try {
                String value = redisTemplate.opsForValue().get(redisKey);
                if (value != null) {
                    return Integer.parseInt(value);
                }
            } catch (Exception e) {
                log.debug("Error getting count for key {}: {}", redisKey, e.getMessage());
            }
        }
        return 0;
    }

    @Override
    public long getRemainingSeconds(String key) {
        // Try to get TTL from Redis (check multiple window sizes)
        for (int windowSeconds : List.of(60, 300, 3600)) {
            String redisKey = buildRedisKey(key, windowSeconds);
            try {
                Long ttl = redisTemplate.getExpire(redisKey);
                if (ttl != null && ttl > 0) {
                    return ttl;
                }
            } catch (Exception e) {
                log.debug("Error getting TTL for key {}: {}", redisKey, e.getMessage());
            }
        }
        return 0;
    }

    @Override
    public void reset() {
        try {
            // Delete all keys with our prefix (use with caution!)
            var keys = redisTemplate.keys(keyPrefix + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("🔄 Cleared {} rate limit keys from Redis", keys.size());
            }
        } catch (Exception e) {
            log.error("Error clearing rate limit keys: {}", e.getMessage());
        }
    }

    /**
     * Build Redis key with namespace and window identifier.
     * 
     * Format: rate_limit:{key}:{windowSeconds}
     * Example: rate_limit:ip:192.168.1.1:60
     * 
     * Window-based keys allow different rate limits for the same client.
     */
    private String buildRedisKey(String key, int windowSeconds) {
        return keyPrefix + ":" + key + ":" + windowSeconds;
    }

    /**
     * Check if Redis connection is healthy.
     * Used for health checks and monitoring.
     * 
     * @return true if Redis is reachable
     */
    public boolean isHealthy() {
        try {
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            log.warn("Redis health check failed: {}", e.getMessage());
            return false;
        }
    }
}
