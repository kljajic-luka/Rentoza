package org.example.rentoza.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Redis-backed implementation of idempotency storage.
 * 
 * <h2>When to Use</h2>
 * <ul>
 *   <li>Multi-instance deployments (horizontal scaling)</li>
 *   <li>High-availability requirements</li>
 *   <li>When idempotency must survive instance restarts</li>
 * </ul>
 * 
 * <h2>Features</h2>
 * <ul>
 *   <li>Distributed locking with SETNX (SET if Not eXists)</li>
 *   <li>Automatic TTL-based expiration via Redis</li>
 *   <li>Atomic operations for consistency</li>
 *   <li>Per-user scoping for security</li>
 * </ul>
 * 
 * <h2>Activation</h2>
 * <p>This bean is created when:
 * <ul>
 *   <li>{@code app.redis.enabled=true}</li>
 *   <li>A valid {@code StringRedisTemplate} bean exists</li>
 * </ul>
 * 
 * <h2>Redis Key Format</h2>
 * <pre>
 * idempotency:{userId}:{idempotencyKey}
 * Example: idempotency:123:550e8400-e29b-41d4-a716-446655440000
 * </pre>
 * 
 * @author Rentoza Platform Team
 * @since Phase 3.1 - Redis Configuration Hardening
 */
@Service
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
@ConditionalOnBean(StringRedisTemplate.class)
@Slf4j
public class RedisIdempotencyService implements IdempotencyStore {

    private static final String REDIS_PREFIX = "idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE
    );

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisIdempotencyService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        log.info("✅ [Idempotency] Redis storage initialized");
        log.info("   → TTL: {} hours", DEFAULT_TTL.toHours());
        log.info("   → Suitable for distributed deployments");
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

        String redisKey = buildRedisKey(idempotencyKey, userId);
        
        try {
            String cached = redisTemplate.opsForValue().get(redisKey);
            if (cached == null) {
                return Optional.empty();
            }

            IdempotencyService.IdempotencyResult result = objectMapper.readValue(
                    cached, IdempotencyService.IdempotencyResult.class);
            
            if (result.getStatus() == IdempotencyService.IdempotencyStatus.PROCESSING) {
                log.debug("[Idempotency] Request in progress: {} for user {}", 
                        maskKey(idempotencyKey), userId);
            } else {
                log.debug("[Idempotency] Returning cached result for key: {} user: {}", 
                        maskKey(idempotencyKey), userId);
            }
            
            return Optional.of(result);
            
        } catch (JsonProcessingException e) {
            log.error("[Idempotency] Failed to deserialize cached result: {}", e.getMessage());
            redisTemplate.delete(redisKey);
            return Optional.empty();
        }
    }

    @Override
    public boolean markProcessing(String idempotencyKey, Long userId, String operationType) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;
        }

        String redisKey = buildRedisKey(idempotencyKey, userId);
        
        IdempotencyService.IdempotencyResult processing = IdempotencyService.IdempotencyResult.builder()
                .status(IdempotencyService.IdempotencyStatus.PROCESSING)
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
            
            log.debug("[Idempotency] Lock not acquired (duplicate): {} user: {}", 
                    maskKey(idempotencyKey), userId);
            return false;
            
        } catch (JsonProcessingException e) {
            log.error("[Idempotency] Failed to serialize processing state: {}", e.getMessage());
            return true; // Fail open - allow request to proceed
        }
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
        
        String redisKey = buildRedisKey(idempotencyKey, userId);
        redisTemplate.delete(redisKey);
        log.debug("[Idempotency] Removed key: {} for user: {}", maskKey(idempotencyKey), userId);
    }

    @Override
    public String getStorageType() {
        return "Redis";
    }

    // ========== PRIVATE METHODS ==========

    private void storeResult(String idempotencyKey, Long userId, 
                             IdempotencyService.IdempotencyStatus status,
                             HttpStatus httpStatus, Object responseBody, String errorMessage) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }

        String redisKey = buildRedisKey(idempotencyKey, userId);
        
        IdempotencyService.IdempotencyResult result = IdempotencyService.IdempotencyResult.builder()
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
        return REDIS_PREFIX + userId + ":" + idempotencyKey;
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
}
