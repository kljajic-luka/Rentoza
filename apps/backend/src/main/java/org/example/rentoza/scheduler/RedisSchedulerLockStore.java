package org.example.rentoza.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Lua script for atomic compare-and-delete.
     * Releases the lock ONLY if the stored token matches the caller's token.
     * Returns 1 on success, 0 if no match (lock not owned by this pod).
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "    return redis.call('del', KEYS[1]) " +
            "else " +
            "    return 0 " +
            "end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    /** Maps taskId → UUID token for locks held by this JVM instance. */
    private final ConcurrentHashMap<String, String> heldTokens = new ConcurrentHashMap<>();

    public RedisSchedulerLockStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        log.info("[SchedulerLock] Initialized REDIS scheduler lock store");
    }

    @Override
    public boolean tryAcquireLock(String taskId, Duration ttl) {
        if (taskId == null || taskId.isBlank()) {
            log.warn("[SchedulerLock] Invalid taskId provided");
            return false; // Fail closed for null/blank input.
        }

        String key = LOCK_KEY_PREFIX + taskId;
        // P1-10 FIX: Use a unique token per acquisition so only the lock’s owner can release it.
        String token = UUID.randomUUID().toString();

        try {
            // SET NX (only if not exists) with TTL - atomic operation
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);

            if (Boolean.TRUE.equals(acquired)) {
                heldTokens.put(taskId, token);
                log.debug("[SchedulerLock] Acquired Redis lock for task: {} (TTL: {})", taskId, ttl);
                return true;
            } else {
                log.debug("[SchedulerLock] Lock already held for task: {}", taskId);
                return false;
            }
        } catch (Exception e) {
            log.error("[SchedulerLock] Error acquiring Redis lock for task {}: {} — FAILING CLOSED",
                    taskId, e.getMessage());
            // P1-10 FIX: Fail closed — do NOT run the job if we cannot confirm lock ownership.
            return false;
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
        String token = heldTokens.remove(taskId);
        if (token == null) {
            log.warn("[SchedulerLock] releaseLock called for task {} but no token found — not owner?", taskId);
            return;
        }

        try {
            // P1-10 FIX: Atomic compare-and-delete — only deletes if we still own the lock.
            Long deleted = redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(key), token);
            if (Long.valueOf(1L).equals(deleted)) {
                log.debug("[SchedulerLock] Released Redis lock for task: {}", taskId);
            } else {
                log.warn("[SchedulerLock] Lock for task {} was NOT released (expired or taken by another pod)", taskId);
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
