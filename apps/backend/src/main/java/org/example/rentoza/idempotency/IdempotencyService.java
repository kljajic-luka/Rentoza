package org.example.rentoza.idempotency;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Enterprise-grade idempotency service facade.
 * 
 * <h2>Architecture</h2>
 * <p>This service delegates to an {@link IdempotencyStore} implementation:
 * <ul>
 *   <li>{@link InMemoryIdempotencyService} - When Redis is disabled (default)</li>
 *   <li>{@link RedisIdempotencyService} - When Redis is enabled</li>
 * </ul>
 * 
 * <h2>Purpose</h2>
 * <p>Prevents duplicate operations during network retries or double-clicks:
 * <ul>
 *   <li>Payment processing (duplicate charges)</li>
 *   <li>Check-in state transitions (duplicate events)</li>
 *   <li>Booking creation (duplicate reservations)</li>
 * </ul>
 * 
 * <h2>Implementation Pattern</h2>
 * <pre>
 * Request 1: X-Idempotency-Key: uuid-123
 *   → Check store: NOT_FOUND
 *   → Store PROCESSING state
 *   → Execute operation
 *   → Store COMPLETED with response
 *   → Return 200 OK
 * 
 * Request 2: X-Idempotency-Key: uuid-123 (retry)
 *   → Check store: FOUND (COMPLETED)
 *   → Return cached response (200 OK)
 *   → Operation NOT re-executed
 * </pre>
 * 
 * <h2>Security Considerations</h2>
 * <ul>
 *   <li>Keys are scoped per-user to prevent cross-user replay attacks</li>
 *   <li>UUID v4 format validation prevents injection</li>
 *   <li>24-hour TTL prevents storage bloat</li>
 *   <li>PROCESSING state prevents concurrent duplicate execution</li>
 * </ul>
 * 
 * <h2>Configuration</h2>
 * <pre>
 * # Use in-memory storage (default)
 * app.redis.enabled=false
 * 
 * # Use Redis storage
 * app.redis.enabled=true
 * spring.data.redis.host=your-redis-host
 * </pre>
 * 
 * @author Rentoza Platform Team
 * @since Phase 3.1 - Redis Configuration Hardening
 */
@Service
@Slf4j
public class IdempotencyService {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE
    );

    private final IdempotencyStore store;

    /**
     * Constructor with store injection.
     * Spring will inject the appropriate implementation based on configuration.
     * 
     * @param store The idempotency store implementation (Redis or In-Memory)
     */
    public IdempotencyService(IdempotencyStore store) {
        this.store = store;
        log.info("✅ [Idempotency] Service initialized with {} storage", store.getStorageType());
    }

    /**
     * Check if a request with this idempotency key has been processed.
     * 
     * @param idempotencyKey Client-provided UUID
     * @param userId Current authenticated user ID (for scoping)
     * @return Optional containing cached result if previously processed
     */
    public Optional<IdempotencyResult> checkIdempotency(String idempotencyKey, Long userId) {
        return store.checkIdempotency(idempotencyKey, userId);
    }

    /**
     * Mark a request as being processed (acquire lock).
     * 
     * @param idempotencyKey Client-provided UUID
     * @param userId Current authenticated user ID
     * @param operationType Type of operation (for logging)
     * @return true if lock acquired, false if already processing
     */
    public boolean markProcessing(String idempotencyKey, Long userId, String operationType) {
        return store.markProcessing(idempotencyKey, userId, operationType);
    }

    /**
     * Store successful operation result.
     * 
     * @param idempotencyKey Client-provided UUID
     * @param userId Current authenticated user ID
     * @param httpStatus Response status code
     * @param responseBody Response body (will be serialized)
     */
    public void storeSuccess(String idempotencyKey, Long userId, 
                             HttpStatus httpStatus, Object responseBody) {
        store.storeSuccess(idempotencyKey, userId, httpStatus, responseBody);
    }

    /**
     * Store failed operation result.
     * 
     * @param idempotencyKey Client-provided UUID
     * @param userId Current authenticated user ID
     * @param httpStatus Response status code
     * @param errorMessage Error message
     */
    public void storeFailure(String idempotencyKey, Long userId, 
                             HttpStatus httpStatus, String errorMessage) {
        store.storeFailure(idempotencyKey, userId, httpStatus, errorMessage);
    }

    /**
     * Remove idempotency record (cleanup after transient errors).
     * 
     * @param idempotencyKey Client-provided UUID
     * @param userId Current authenticated user ID
     */
    public void remove(String idempotencyKey, Long userId) {
        store.remove(idempotencyKey, userId);
    }

    /**
     * Get the underlying storage type.
     * 
     * @return "Redis" or "In-Memory"
     */
    public String getStorageType() {
        return store.getStorageType();
    }

    // ========== STATIC UTILITY METHODS ==========

    /**
     * Generate a new idempotency key (utility for clients).
     * 
     * @return A new UUID v4 string
     */
    public static String generateKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * Validate idempotency key format (UUID v4).
     * 
     * @param key The key to validate
     * @return true if valid UUID v4 format
     */
    public static boolean isValidIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return UUID_PATTERN.matcher(key).matches();
    }

    // ========== INNER CLASSES ==========

    /**
     * Idempotency operation status.
     */
    public enum IdempotencyStatus {
        /** Request is currently being processed */
        PROCESSING,
        /** Request completed successfully */
        COMPLETED,
        /** Request failed (cached for idempotency) */
        FAILED
    }

    /**
     * Cached idempotency result.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IdempotencyResult {
        private IdempotencyStatus status;
        private String operationType;
        private Integer httpStatus;
        private String responseBody;
        private String errorMessage;
        private Instant startedAt;
        private Instant completedAt;
    }

    /**
     * Exception for invalid idempotency key format.
     */
    public static class InvalidIdempotencyKeyException extends RuntimeException {
        public InvalidIdempotencyKeyException(String message) {
            super(message);
        }
    }
}
