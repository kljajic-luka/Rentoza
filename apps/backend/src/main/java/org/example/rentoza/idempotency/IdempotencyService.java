package org.example.rentoza.idempotency;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Enterprise-grade idempotency service using Redis for distributed caching.
 * 
 * <h2>Phase 1 Critical Fix: Idempotency Protection</h2>
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
 *   → Check Redis: NOT_FOUND
 *   → Store PROCESSING state
 *   → Execute operation
 *   → Store COMPLETED with response
 *   → Return 200 OK
 * 
 * Request 2: X-Idempotency-Key: uuid-123 (retry)
 *   → Check Redis: FOUND (COMPLETED)
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
 * @see IdempotencyInterceptor for HTTP header extraction
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private static final String REDIS_PREFIX = "idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE
    );

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Check if a request with this idempotency key has been processed.
     * 
     * @param idempotencyKey Client-provided UUID
     * @param userId Current authenticated user ID (for scoping)
     * @return Optional containing cached result if previously processed
     */
    public Optional<IdempotencyResult> checkIdempotency(String idempotencyKey, Long userId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty(); // No key = no idempotency check
        }

        // Validate key format (UUID v4)
        if (!isValidIdempotencyKey(idempotencyKey)) {
            log.warn("[Idempotency] Invalid key format rejected: {} for user {}", 
                    maskKey(idempotencyKey), userId);
            throw new InvalidIdempotencyKeyException("Idempotency key must be a valid UUID v4");
        }

        String redisKey = buildRedisKey(idempotencyKey, userId);
        String cached = redisTemplate.opsForValue().get(redisKey);

        if (cached == null) {
            return Optional.empty();
        }

        try {
            IdempotencyResult result = objectMapper.readValue(cached, IdempotencyResult.class);
            
            // If still processing, indicate conflict
            if (result.getStatus() == IdempotencyStatus.PROCESSING) {
                log.info("[Idempotency] Request in progress: {} for user {}", 
                        maskKey(idempotencyKey), userId);
                return Optional.of(result);
            }

            log.info("[Idempotency] Returning cached result for key: {} user: {}", 
                    maskKey(idempotencyKey), userId);
            return Optional.of(result);
            
        } catch (JsonProcessingException e) {
            log.error("[Idempotency] Failed to deserialize cached result: {}", e.getMessage());
            // Delete corrupted cache entry
            redisTemplate.delete(redisKey);
            return Optional.empty();
        }
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
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true; // No key = proceed without idempotency
        }

        String redisKey = buildRedisKey(idempotencyKey, userId);
        
        IdempotencyResult processing = IdempotencyResult.builder()
                .status(IdempotencyStatus.PROCESSING)
                .operationType(operationType)
                .startedAt(Instant.now())
                .build();

        try {
            String json = objectMapper.writeValueAsString(processing);
            
            // SETNX: Only set if not exists (atomic lock acquisition)
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, json, DEFAULT_TTL);
            
            if (Boolean.TRUE.equals(acquired)) {
                log.debug("[Idempotency] Lock acquired for key: {} user: {} operation: {}", 
                        maskKey(idempotencyKey), userId, operationType);
                return true;
            }
            
            log.info("[Idempotency] Lock not acquired (duplicate): {} user: {}", 
                    maskKey(idempotencyKey), userId);
            return false;
            
        } catch (JsonProcessingException e) {
            log.error("[Idempotency] Failed to serialize processing state: {}", e.getMessage());
            return true; // Fail open - allow request to proceed
        }
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
        storeResult(idempotencyKey, userId, IdempotencyStatus.COMPLETED, httpStatus, responseBody, null);
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
        storeResult(idempotencyKey, userId, IdempotencyStatus.FAILED, httpStatus, null, errorMessage);
    }

    /**
     * Remove idempotency record (cleanup after transient errors).
     */
    public void remove(String idempotencyKey, Long userId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        
        String redisKey = buildRedisKey(idempotencyKey, userId);
        redisTemplate.delete(redisKey);
        log.debug("[Idempotency] Removed key: {} for user: {}", maskKey(idempotencyKey), userId);
    }

    /**
     * Generate a new idempotency key (utility for clients).
     */
    public static String generateKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * Validate idempotency key format (UUID v4).
     */
    public static boolean isValidIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return UUID_PATTERN.matcher(key).matches();
    }

    // ========== PRIVATE METHODS ==========

    private void storeResult(String idempotencyKey, Long userId, IdempotencyStatus status,
                             HttpStatus httpStatus, Object responseBody, String errorMessage) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }

        String redisKey = buildRedisKey(idempotencyKey, userId);
        
        IdempotencyResult result = IdempotencyResult.builder()
                .status(status)
                .httpStatus(httpStatus.value())
                .responseBody(responseBody != null ? serializeResponseBody(responseBody) : null)
                .errorMessage(errorMessage)
                .completedAt(Instant.now())
                .build();

        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(redisKey, json, DEFAULT_TTL);
            
            log.debug("[Idempotency] Stored {} result for key: {} user: {}", 
                    status, maskKey(idempotencyKey), userId);
            
        } catch (JsonProcessingException e) {
            log.error("[Idempotency] Failed to serialize result: {}", e.getMessage());
        }
    }

    private String serializeResponseBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.warn("[Idempotency] Failed to serialize response body: {}", e.getMessage());
            return null;
        }
    }

    private String buildRedisKey(String idempotencyKey, Long userId) {
        // Scope by user to prevent cross-user replay attacks
        return REDIS_PREFIX + userId + ":" + idempotencyKey;
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) {
            return "***";
        }
        return key.substring(0, 8) + "...";
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
