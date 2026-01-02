package org.example.rentoza.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Service for ensuring scheduler idempotency.
 * 
 * <p>Prevents duplicate execution of scheduled tasks in clustered environments
 * or when schedulers accidentally fire multiple times (e.g., due to DST transitions).
 * 
 * <h3>How it works:</h3>
 * <ol>
 *   <li>Before executing a task, check if a lock exists for the task ID</li>
 *   <li>If no lock exists, acquire lock with TTL and execute</li>
 *   <li>If lock exists and not expired, skip execution</li>
 * </ol>
 * 
 * <h3>Storage Options:</h3>
 * <ul>
 *   <li><b>Redis:</b> Used when available (clustered deployments)</li>
 *   <li><b>In-Memory:</b> Fallback for single-instance deployments</li>
 * </ul>
 * 
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * @Scheduled(cron = "0 0 * * * *", zone = "Europe/Belgrade")
 * public void hourlyTask() {
 *     String taskId = "checkout-window-" + LocalDate.now();
 *     
 *     if (idempotencyService.tryAcquireLock(taskId, Duration.ofHours(1))) {
 *         try {
 *             // ... actual task logic
 *         } finally {
 *             // Lock auto-expires via TTL, no explicit release needed
 *         }
 *     } else {
 *         log.debug("Skipping duplicate execution: {}", taskId);
 *     }
 * }
 * }</pre>
 * 
 * <p><b>Phase 3 - Enterprise Hardening:</b> Part of Time Window Logic Improvement Plan
 * 
 * @since 2026-01 (Phase 3)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SchedulerIdempotencyService {

    private static final String LOCK_PREFIX = "scheduler:lock:";

    /**
     * Redis template - may be null if Redis is not configured.
     */
    private final StringRedisTemplate redisTemplate;

    /**
     * In-memory fallback storage for non-clustered deployments.
     */
    private final ConcurrentMap<String, Instant> inMemoryLocks = new ConcurrentHashMap<>();

    /**
     * Try to acquire an idempotency lock for a scheduled task.
     * 
     * <p>Uses Redis if available, falls back to in-memory storage.
     * 
     * @param taskId Unique identifier for this task execution (e.g., "checkout-window-2026-01-02")
     * @param ttl How long the lock should be held (prevents re-execution within this window)
     * @return true if lock was acquired (task should execute), false if already locked (skip)
     */
    public boolean tryAcquireLock(String taskId, Duration ttl) {
        if (taskId == null || taskId.isBlank()) {
            log.warn("[Idempotency] Invalid taskId provided, allowing execution");
            return true;
        }

        try {
            if (isRedisAvailable()) {
                return tryAcquireRedisLock(taskId, ttl);
            } else {
                return tryAcquireInMemoryLock(taskId, ttl);
            }
        } catch (Exception e) {
            log.warn("[Idempotency] Error checking lock for {}, allowing execution: {}", 
                    taskId, e.getMessage());
            return true; // Fail open - better to execute twice than not at all
        }
    }

    /**
     * Check if a task is currently locked (without attempting to acquire).
     * 
     * @param taskId Task identifier
     * @return true if task is locked (should not execute)
     */
    public boolean isLocked(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }

        try {
            if (isRedisAvailable()) {
                String key = LOCK_PREFIX + taskId;
                return Boolean.TRUE.equals(redisTemplate.hasKey(key));
            } else {
                return isInMemoryLocked(taskId);
            }
        } catch (Exception e) {
            log.warn("[Idempotency] Error checking lock status for {}: {}", taskId, e.getMessage());
            return false;
        }
    }

    /**
     * Manually release a lock (typically not needed - locks auto-expire).
     * 
     * @param taskId Task identifier
     */
    public void releaseLock(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }

        try {
            if (isRedisAvailable()) {
                String key = LOCK_PREFIX + taskId;
                redisTemplate.delete(key);
                log.debug("[Idempotency] Released Redis lock: {}", taskId);
            } else {
                inMemoryLocks.remove(taskId);
                log.debug("[Idempotency] Released in-memory lock: {}", taskId);
            }
        } catch (Exception e) {
            log.warn("[Idempotency] Error releasing lock for {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Clean up expired in-memory locks.
     * Called periodically to prevent memory leaks.
     */
    public void cleanupExpiredLocks() {
        Instant now = Instant.now();
        int removed = 0;

        var iterator = inMemoryLocks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().isBefore(now)) {
                iterator.remove();
                removed++;
            }
        }

        if (removed > 0) {
            log.debug("[Idempotency] Cleaned up {} expired in-memory locks", removed);
        }
    }

    // ========================================================================
    // PRIVATE METHODS
    // ========================================================================

    private boolean isRedisAvailable() {
        try {
            return redisTemplate != null && redisTemplate.getConnectionFactory() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tryAcquireRedisLock(String taskId, Duration ttl) {
        String key = LOCK_PREFIX + taskId;
        String value = Instant.now().toString();

        // SET NX (only if not exists) with TTL
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, value, ttl);

        if (Boolean.TRUE.equals(acquired)) {
            log.debug("[Idempotency] Acquired Redis lock: {} (TTL: {})", taskId, ttl);
            return true;
        } else {
            log.debug("[Idempotency] Lock already held: {} - skipping execution", taskId);
            return false;
        }
    }

    private boolean tryAcquireInMemoryLock(String taskId, Duration ttl) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);

        // Clean up expired locks first
        cleanupExpiredLocks();

        // Try to acquire lock
        Instant existingExpiry = inMemoryLocks.get(taskId);
        
        if (existingExpiry != null && existingExpiry.isAfter(now)) {
            log.debug("[Idempotency] In-memory lock already held: {} (expires: {})", 
                    taskId, existingExpiry);
            return false;
        }

        // Acquire new lock
        inMemoryLocks.put(taskId, expiresAt);
        log.debug("[Idempotency] Acquired in-memory lock: {} (expires: {})", taskId, expiresAt);
        return true;
    }

    private boolean isInMemoryLocked(String taskId) {
        Instant expiry = inMemoryLocks.get(taskId);
        if (expiry == null) {
            return false;
        }
        if (expiry.isBefore(Instant.now())) {
            inMemoryLocks.remove(taskId);
            return false;
        }
        return true;
    }
}
