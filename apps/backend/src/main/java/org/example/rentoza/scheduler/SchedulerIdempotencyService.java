package org.example.rentoza.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;

/**
 * Facade service for scheduler lock operations.
 * 
 * <p>This service provides a clean API for scheduled tasks to prevent
 * concurrent execution across multiple application instances. It delegates
 * to the appropriate {@link SchedulerLockStore} implementation based on
 * the application configuration.</p>
 * 
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * @Scheduled(fixedRate = 60000)
 * public void myScheduledTask() {
 *     if (!schedulerIdempotencyService.tryAcquireLock("my-task", Duration.ofMinutes(5))) {
 *         log.debug("Task already running on another instance");
 *         return;
 *     }
 *     try {
 *         // Execute task logic
 *     } finally {
 *         schedulerIdempotencyService.releaseLock("my-task");
 *     }
 * }
 * }</pre>
 * 
 * <h3>Architecture:</h3>
 * <ul>
 *   <li><b>InMemorySchedulerLockStore</b>: Default when Redis is disabled</li>
 *   <li><b>RedisSchedulerLockStore</b>: Distributed locks when Redis is enabled</li>
 * </ul>
 * 
 * @see SchedulerLockStore
 * @see InMemorySchedulerLockStore
 * @see RedisSchedulerLockStore
 */
@Service
public class SchedulerIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerIdempotencyService.class);

    private final SchedulerLockStore lockStore;

    /**
     * Constructor injection ensures the correct implementation is used.
     * Spring will select based on @Primary and @ConditionalOnProperty annotations.
     * 
     * @param lockStore The scheduler lock store implementation
     */
    public SchedulerIdempotencyService(SchedulerLockStore lockStore) {
        this.lockStore = lockStore;
    }

    @PostConstruct
    public void init() {
        log.info("[SchedulerIdempotency] Initialized with {} storage", lockStore.getStorageType());
    }

    /**
     * Attempt to acquire a distributed lock for a scheduled task.
     * 
     * <p>This method provides distributed mutex functionality for scheduled tasks.
     * When multiple instances of the application are running, only one will
     * successfully acquire the lock and execute the task.</p>
     * 
     * @param taskId Unique identifier for the scheduled task (e.g., "payment-reminder", "cleanup-job")
     * @param ttl Time-to-live for the lock - should be longer than expected task duration
     * @return true if lock was acquired (caller should execute task), false if another instance holds the lock
     */
    public boolean tryAcquireLock(String taskId, Duration ttl) {
        if (taskId == null || taskId.isBlank()) {
            log.warn("[SchedulerIdempotency] Invalid taskId provided, allowing execution");
            return true;
        }

        try {
            return lockStore.tryAcquireLock(taskId, ttl);
        } catch (Exception e) {
            log.warn("[SchedulerIdempotency] Error acquiring lock for {}, allowing execution: {}", 
                    taskId, e.getMessage());
            // Fail open - better to execute twice than not at all
            return true;
        }
    }

    /**
     * Check if a task is currently locked without attempting to acquire.
     * 
     * <p>Useful for monitoring or deciding whether to skip a task invocation.</p>
     * 
     * @param taskId Task identifier
     * @return true if task is locked (should not execute)
     */
    public boolean isLocked(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }

        try {
            return lockStore.isLocked(taskId);
        } catch (Exception e) {
            log.warn("[SchedulerIdempotency] Error checking lock status for {}: {}", 
                    taskId, e.getMessage());
            return false;
        }
    }

    /**
     * Manually release a lock before its TTL expires.
     * 
     * <p>Typically not needed as locks auto-expire, but useful when a task
     * completes early and you want to allow the next execution sooner.</p>
     * 
     * @param taskId Task identifier
     */
    public void releaseLock(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }

        try {
            lockStore.releaseLock(taskId);
        } catch (Exception e) {
            log.warn("[SchedulerIdempotency] Error releasing lock for {}: {}", 
                    taskId, e.getMessage());
        }
    }

    /**
     * Get the storage type currently in use.
     * Useful for diagnostics and logging.
     * 
     * @return Storage type identifier
     */
    public String getStorageType() {
        return lockStore.getStorageType();
    }
}
