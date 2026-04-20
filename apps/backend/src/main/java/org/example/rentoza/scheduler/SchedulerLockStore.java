package org.example.rentoza.scheduler;

import java.time.Duration;

/**
 * Interface for distributed scheduler lock storage.
 * 
 * Implementations provide atomic lock acquisition for preventing
 * concurrent execution of scheduled tasks across multiple instances.
 * 
 * <p>Design Pattern: Strategy Pattern</p>
 * <p>This allows swapping between Redis (distributed) and in-memory (single-instance)
 * implementations based on configuration.</p>
 */
public interface SchedulerLockStore {

    /**
     * Attempt to acquire a distributed lock for a scheduled task.
     * 
     * @param taskId Unique identifier for the scheduled task
     * @param ttl Time-to-live for the lock (auto-expires after this duration)
     * @return true if lock was acquired (task should execute), false if lock is held by another instance
     */
    boolean tryAcquireLock(String taskId, Duration ttl);

    /**
     * Check if a task is currently locked without attempting to acquire.
     * 
     * @param taskId Unique identifier for the scheduled task
     * @return true if the task is currently locked
     */
    boolean isLocked(String taskId);

    /**
     * Manually release a lock before its TTL expires.
     * Typically not needed as locks auto-expire, but useful for cleanup.
     * 
     * @param taskId Unique identifier for the scheduled task
     */
    void releaseLock(String taskId);

    /**
     * Get the storage type for logging and diagnostics.
     * 
     * @return Storage type identifier (e.g., "redis", "in-memory")
     */
    String getStorageType();
}
