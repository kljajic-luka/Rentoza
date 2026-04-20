package org.example.chatservice.service;

import lombok.extern.slf4j.Slf4j;
import org.example.chatservice.dto.MessageDTO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory idempotency service for retry-safe message sending.
 * 
 * Prevents duplicate messages when clients retry failed sends.
 * Stores idempotency keys with their results for a TTL window.
 * 
 * Production: Replace ConcurrentHashMap with Redis for distributed deployments.
 */
@Service
@Slf4j
public class IdempotencyService {

    private static final long TTL_MINUTES = 10; // Keys expire after 10 minutes

    private final Map<String, IdempotencyEntry> idempotencyStore = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler;

    public IdempotencyService() {
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idempotency-cleanup");
            t.setDaemon(true);
            return t;
        });
        // Cleanup expired entries every 5 minutes
        this.cleanupScheduler.scheduleAtFixedRate(this::cleanupExpired, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * Check if an idempotency key has already been processed.
     * 
     * @param key The idempotency key (UUID from client)
     * @return The cached MessageDTO if already processed, null if new
     */
    public MessageDTO getExistingResult(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        IdempotencyEntry entry = idempotencyStore.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            idempotencyStore.remove(key);
            return null;
        }
        log.info("[Idempotency] Returning cached result for key {}", key.substring(0, Math.min(8, key.length())));
        return entry.result;
    }

    /**
     * Store a result for an idempotency key.
     * 
     * @param key The idempotency key
     * @param result The message DTO result to cache
     */
    public void storeResult(String key, MessageDTO result) {
        if (key == null || key.isBlank()) {
            return;
        }
        idempotencyStore.put(key, new IdempotencyEntry(result, Instant.now()));
        log.debug("[Idempotency] Stored result for key {}", key.substring(0, Math.min(8, key.length())));
    }

    /**
     * Check if a key is currently being processed (for concurrent request protection).
     * Returns true if the key was successfully claimed (first caller wins).
     */
    public boolean tryClaimKey(String key) {
        if (key == null || key.isBlank()) {
            return true; // No key = always proceed
        }
        IdempotencyEntry existing = idempotencyStore.putIfAbsent(
                key, new IdempotencyEntry(null, Instant.now()));
        return existing == null; // True if we were the first to claim it
    }

    /**
     * Release a previously claimed key (e.g., when the operation fails).
     * This allows the client to retry with the same idempotency key.
     */
    public void releaseKey(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        IdempotencyEntry entry = idempotencyStore.get(key);
        // Only release if the entry has no result (i.e., was just claimed, not completed)
        if (entry != null && entry.result == null) {
            idempotencyStore.remove(key);
            log.debug("[Idempotency] Released claimed key {}", key.substring(0, Math.min(8, key.length())));
        }
    }

    private void cleanupExpired() {
        int before = idempotencyStore.size();
        idempotencyStore.entrySet().removeIf(entry -> entry.getValue().isExpired());
        int after = idempotencyStore.size();
        if (before > after) {
            log.debug("[Idempotency] Cleaned up {} expired entries", before - after);
        }
    }

    private static class IdempotencyEntry {
        final MessageDTO result;
        final Instant createdAt;

        IdempotencyEntry(MessageDTO result, Instant createdAt) {
            this.result = result;
            this.createdAt = createdAt;
        }

        boolean isExpired() {
            return Instant.now().isAfter(createdAt.plusSeconds(TTL_MINUTES * 60));
        }
    }
}
