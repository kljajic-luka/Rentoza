package org.example.rentoza.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * In-memory implementation of idempotency storage.
 * 
 * <h2>When to Use</h2>
 * <ul>
 *   <li>Single-instance deployments (Cloud Run with min-instances=1)</li>
 *   <li>Development and testing environments</li>
 *   <li>When Redis is not available or not needed</li>
 * </ul>
 * 
 * <h2>Limitations</h2>
 * <ul>
 *   <li>Not suitable for multi-instance deployments (no distributed state)</li>
 *   <li>Data lost on instance restart (acceptable for idempotency)</li>
 *   <li>Memory usage grows with concurrent operations</li>
 * </ul>
 * 
 * <h2>Features</h2>
 * <ul>
 *   <li>Thread-safe using ConcurrentHashMap</li>
 *   <li>Automatic TTL-based expiration</li>
 *   <li>Periodic cleanup of expired entries</li>
 *   <li>Per-user scoping for security</li>
 * </ul>
 * 
 * <h2>Activation</h2>
 * <p>This bean is created when {@code app.redis.enabled=false} (or not set).
 * It is marked as {@code @Primary} so it takes precedence when Redis is disabled.
 * 
 * @author Rentoza Platform Team
 * @since Phase 3.1 - Redis Configuration Hardening
 */
@Service
@Slf4j
public class InMemoryIdempotencyService implements IdempotencyStore {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE
    );

    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    /**
     * Cache entry with expiration support.
     */
    private record CacheEntry(IdempotencyService.IdempotencyResult result, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    public InMemoryIdempotencyService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        log.info("✅ [Idempotency] In-Memory storage initialized");
        log.info("   → TTL: {} hours", DEFAULT_TTL.toHours());
        log.info("   → Suitable for single-instance deployments");
    }

    @Override
    public Optional<IdempotencyService.IdempotencyResult> checkIdempotency(String idempotencyKey, Long userId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }

        if (!isValidIdempotencyKey(idempotencyKey)) {
            log.warn("[Idempotency] Invalid key format rejected: {} for user {}", 
                    maskKey(idempotencyKey), userId);
            throw new IdempotencyService.InvalidIdempotencyKeyException("Idempotency key must be a valid UUID v4");
        }

        String cacheKey = buildCacheKey(idempotencyKey, userId);
        CacheEntry entry = cache.get(cacheKey);

        if (entry == null || entry.isExpired()) {
            if (entry != null && entry.isExpired()) {
                cache.remove(cacheKey);
            }
            return Optional.empty();
        }

        IdempotencyService.IdempotencyResult result = entry.result();
        
        if (result.getStatus() == IdempotencyService.IdempotencyStatus.PROCESSING) {
            log.debug("[Idempotency] Request in progress: {} for user {}", 
                    maskKey(idempotencyKey), userId);
        } else {
            log.debug("[Idempotency] Returning cached result for key: {} user: {}", 
                    maskKey(idempotencyKey), userId);
        }
        
        return Optional.of(result);
    }

    @Override
    public boolean markProcessing(String idempotencyKey, Long userId, String operationType) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true; // No key = proceed without idempotency
        }

        String cacheKey = buildCacheKey(idempotencyKey, userId);
        
        IdempotencyService.IdempotencyResult processing = IdempotencyService.IdempotencyResult.builder()
                .status(IdempotencyService.IdempotencyStatus.PROCESSING)
                .operationType(operationType)
                .startedAt(Instant.now())
                .build();

        CacheEntry newEntry = new CacheEntry(processing, Instant.now().plus(DEFAULT_TTL));
        
        // putIfAbsent returns null if key was not present (lock acquired)
        CacheEntry existing = cache.putIfAbsent(cacheKey, newEntry);
        
        if (existing == null) {
            log.debug("[Idempotency] Lock acquired for key: {} user: {} operation: {}", 
                    maskKey(idempotencyKey), userId, operationType);
            return true;
        }
        
        // Check if existing entry is expired
        if (existing.isExpired()) {
            if (cache.replace(cacheKey, existing, newEntry)) {
                log.debug("[Idempotency] Lock acquired (replaced expired) for key: {} user: {}", 
                        maskKey(idempotencyKey), userId);
                return true;
            }
        }
        
        log.debug("[Idempotency] Lock not acquired (duplicate): {} user: {}", 
                maskKey(idempotencyKey), userId);
        return false;
    }

    @Override
    public void storeSuccess(String idempotencyKey, Long userId, HttpStatus httpStatus, Object responseBody) {
        storeResult(idempotencyKey, userId, IdempotencyService.IdempotencyStatus.COMPLETED, 
                httpStatus, responseBody, null);
    }

    @Override
    public void storeFailure(String idempotencyKey, Long userId, HttpStatus httpStatus, String errorMessage) {
        storeResult(idempotencyKey, userId, IdempotencyService.IdempotencyStatus.FAILED, 
                httpStatus, null, errorMessage);
    }

    @Override
    public void remove(String idempotencyKey, Long userId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        
        String cacheKey = buildCacheKey(idempotencyKey, userId);
        cache.remove(cacheKey);
        log.debug("[Idempotency] Removed key: {} for user: {}", maskKey(idempotencyKey), userId);
    }

    @Override
    public String getStorageType() {
        return "In-Memory";
    }

    /**
     * Periodic cleanup of expired entries.
     * Runs every 5 minutes to prevent memory leaks.
     */
    @Scheduled(fixedRate = 300_000) // 5 minutes
    public void cleanupExpiredEntries() {
        Instant now = Instant.now();
        AtomicInteger removed = new AtomicInteger(0);
        
        cache.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                removed.incrementAndGet();
                return true;
            }
            return false;
        });
        
        if (removed.get() > 0) {
            log.debug("[Idempotency] Cleaned up {} expired entries, {} remaining", 
                    removed.get(), cache.size());
        }
    }

    // ========== PRIVATE METHODS ==========

    private void storeResult(String idempotencyKey, Long userId, 
                             IdempotencyService.IdempotencyStatus status,
                             HttpStatus httpStatus, Object responseBody, String errorMessage) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }

        String cacheKey = buildCacheKey(idempotencyKey, userId);
        
        IdempotencyService.IdempotencyResult result = IdempotencyService.IdempotencyResult.builder()
                .status(status)
                .httpStatus(httpStatus.value())
                .responseBody(responseBody != null ? serializeResponseBody(responseBody) : null)
                .errorMessage(errorMessage)
                .completedAt(Instant.now())
                .build();

        CacheEntry entry = new CacheEntry(result, Instant.now().plus(DEFAULT_TTL));
        cache.put(cacheKey, entry);
        
        log.debug("[Idempotency] Stored {} result for key: {} user: {}", 
                status, maskKey(idempotencyKey), userId);
    }

    private String serializeResponseBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.warn("[Idempotency] Failed to serialize response body: {}", e.getMessage());
            return null;
        }
    }

    private String buildCacheKey(String idempotencyKey, Long userId) {
        return userId + ":" + idempotencyKey;
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) {
            return "***";
        }
        return key.substring(0, 8) + "...";
    }

    private boolean isValidIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return UUID_PATTERN.matcher(key).matches();
    }

    /**
     * Get current cache statistics for monitoring.
     * 
     * @return Number of entries in cache
     */
    public int getCacheSize() {
        return cache.size();
    }
}
