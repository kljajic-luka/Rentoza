package org.example.rentoza.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis-based implementation of scheduler lock storage.
 * 
 * <p>This implementation is activated ONLY when:
 * <ul>
 *   <li>Redis is explicitly enabled (app.redis.enabled=true)</li>
 *   <li>StringRedisTemplate bean is available in the context</li>
 * </ul>
 * </p>
 * 
 * <h3>Characteristics:</h3>
 * <ul>
 *   <li>Distributed locks across multiple application instances</li>
 *   <li>Atomic lock acquisition using Redis SETNX</li>
 *   <li>Automatic TTL-based expiration handled by Redis</li>
 *   <li>High availability when using Redis cluster/sentinel</li>
 * </ul>
 * 
 * <h3>Key Pattern:</h3>
 * <code>scheduler:lock:{taskId}</code>
 * 
 * @see SchedulerLockStore
 * @see InMemorySchedulerLockStore
 */
@Service
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisSchedulerLockStore implements SchedulerLockStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSchedulerLockStore.class);
    private static final String LOCK_KEY_PREFIX = "scheduler:lock:";

    private final StringRedisTemplate redisTemplate;

    public RedisSchedulerLockStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        log.info("[SchedulerLock] Initialized REDIS scheduler lock store");
    }

    @Override
    public boolean tryAcquireLock(String taskId, Duration ttl) {
        if (taskId == null || taskId.isBlank()) {
            log.warn("[SchedulerLock] Invalid taskId provided");
            return true; // Allow execution for invalid input
        }

        String key = LOCK_KEY_PREFIX + taskId;
        String value = Instant.now().toString();

        try {
            // SET NX (only if not exists) with TTL - atomic operation
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, value, ttl);

            if (Boolean.TRUE.equals(acquired)) {
                log.debug("[SchedulerLock] Acquired Redis lock for task: {} (TTL: {})", taskId, ttl);
                return true;
            } else {
                log.debug("[SchedulerLock] Lock already held for task: {}", taskId);
                return false;
            }
        } catch (Exception e) {
            log.error("[SchedulerLock] Error acquiring Redis lock for task {}: {}", 
                    taskId, e.getMessage());
            // Fail open - allow execution on Redis errors
            return true;
        }
    }

    @Override
    public boolean isLocked(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }

        String key = LOCK_KEY_PREFIX + taskId;

        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("[SchedulerLock] Error checking lock status for task {}: {}", 
                    taskId, e.getMessage());
            return false;
        }
    }

    @Override
    public void releaseLock(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }

        String key = LOCK_KEY_PREFIX + taskId;

        try {
            Boolean deleted = redisTemplate.delete(key);
            if (Boolean.TRUE.equals(deleted)) {
                log.debug("[SchedulerLock] Released Redis lock for task: {}", taskId);
            }
        } catch (Exception e) {
            log.warn("[SchedulerLock] Error releasing lock for task {}: {}", 
                    taskId, e.getMessage());
        }
    }

    @Override
    public String getStorageType() {
        return "redis";
    }
}
