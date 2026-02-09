package org.example.rentoza.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of scheduler lock storage.
 * 
 * <p>This is the PRIMARY (default) implementation that is used when:
 * <ul>
 *   <li>Redis is explicitly disabled (app.redis.enabled=false)</li>
 *   <li>Redis configuration is missing (matchIfMissing=true)</li>
 * </ul>
 * </p>
 * 
 * <h3>Characteristics:</h3>
 * <ul>
 *   <li>Zero external dependencies</li>
 *   <li>Thread-safe using ConcurrentHashMap</li>
 *   <li>TTL-based automatic expiration</li>
 *   <li>Periodic cleanup of expired locks</li>
 * </ul>
 * 
 * <h3>Limitations:</h3>
 * <ul>
 *   <li>Locks are not shared across application instances</li>
 *   <li>Data is lost on application restart</li>
 *   <li>Suitable only for single-instance deployments or non-critical deduplication</li>
 * </ul>
 * 
 * @see SchedulerLockStore
 * @see RedisSchedulerLockStore
 */
@Service
@Primary
@ConditionalOnProperty(
    name = "app.redis.enabled",
    havingValue = "false",
    matchIfMissing = true
)
public class InMemorySchedulerLockStore implements SchedulerLockStore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySchedulerLockStore.class);

    /**
     * Thread-safe map storing lock expiration times.
     * Key: taskId
     * Value: Expiration timestamp
     */
    private final Map<String, Instant> locks = new ConcurrentHashMap<>();

    public InMemorySchedulerLockStore() {
        log.info("[SchedulerLock] Initialized IN-MEMORY scheduler lock store (Redis disabled)");
    }

    @Override
    public boolean tryAcquireLock(String taskId, Duration ttl) {
        if (taskId == null || taskId.isBlank()) {
            log.warn("[SchedulerLock] Invalid taskId provided");
            return true; // Allow execution for invalid input
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);

        // Check existing lock
        Instant existingExpiry = locks.get(taskId);
        if (existingExpiry != null && existingExpiry.isAfter(now)) {
            log.debug("[SchedulerLock] Lock already held for task: {} (expires: {})", 
                    taskId, existingExpiry);
            return false;
        }

        // Acquire lock
        locks.put(taskId, expiresAt);
        log.debug("[SchedulerLock] Acquired in-memory lock for task: {} (expires: {})", 
                taskId, expiresAt);
        return true;
    }

    @Override
    public boolean isLocked(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }

        Instant expiry = locks.get(taskId);
        if (expiry == null) {
            return false;
        }

        if (expiry.isBefore(Instant.now())) {
            // Lock expired, remove it
            locks.remove(taskId);
            return false;
        }

        return true;
    }

    @Override
    public void releaseLock(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }

        if (locks.remove(taskId) != null) {
            log.debug("[SchedulerLock] Released in-memory lock for task: {}", taskId);
        }
    }

    @Override
    public String getStorageType() {
        return "in-memory";
    }

    /**
     * Scheduled cleanup of expired locks to prevent memory leaks.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedRate = 300_000) // 5 minutes
    public void cleanupExpiredLocks() {
        Instant now = Instant.now();
        int removed = 0;

        var iterator = locks.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().isBefore(now)) {
                iterator.remove();
                removed++;
            }
        }

        if (removed > 0) {
            log.debug("[SchedulerLock] Cleaned up {} expired in-memory locks", removed);
        }
    }

    /**
     * Get current lock count for monitoring/diagnostics.
     */
    public int getLockCount() {
        return locks.size();
    }
}
