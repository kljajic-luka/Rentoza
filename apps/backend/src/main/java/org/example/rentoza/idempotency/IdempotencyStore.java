package org.example.rentoza.idempotency;

import org.springframework.http.HttpStatus;

import java.util.Optional;

/**
 * Interface for idempotency operations.
 * 
 * <h2>Purpose</h2>
 * <p>Provides a contract for idempotency storage that can be implemented
 * by different backends (Redis, In-Memory, Database).
 * 
 * <h2>Idempotency Pattern</h2>
 * <p>Prevents duplicate operations when clients retry requests:
 * <ol>
 *   <li>Client sends request with X-Idempotency-Key header</li>
 *   <li>Server checks if key exists → return cached response</li>
 *   <li>If not exists → mark as PROCESSING, execute, store result</li>
 *   <li>Retry requests get cached response without re-execution</li>
 * </ol>
 * 
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link InMemoryIdempotencyService} - Single instance deployments</li>
 *   <li>{@link RedisIdempotencyService} - Distributed/clustered deployments</li>
 * </ul>
 * 
 * @author Rentoza Platform Team
 * @since Phase 3.1 - Idempotency Abstraction
 */
public interface IdempotencyStore {

    /**
     * Check if a request with this idempotency key has been processed.
     * 
     * @param idempotencyKey Client-provided UUID
     * @param userId Current authenticated user ID (for scoping)
     * @return Optional containing cached result if previously processed
     */
    default Optional<IdempotencyService.IdempotencyResult> checkIdempotency(String idempotencyKey, Long userId) {
        return checkIdempotency(idempotencyKey, userId, null);
    }

    Optional<IdempotencyService.IdempotencyResult> checkIdempotency(String idempotencyKey, Long userId, String scope);

    /**
     * Mark a request as being processed (acquire lock).
     * 
     * @param idempotencyKey Client-provided UUID
     * @param userId Current authenticated user ID
     * @param operationType Type of operation (for logging)
     * @return true if lock acquired, false if already processing
     */
    default boolean markProcessing(String idempotencyKey, Long userId, String operationType) {
        return markProcessing(idempotencyKey, userId, operationType, null);
    }

    boolean markProcessing(String idempotencyKey, Long userId, String operationType, String scope);

    /**
     * Store successful operation result.
     * 
     * @param idempotencyKey Client-provided UUID
     * @param userId Current authenticated user ID
     * @param httpStatus Response status code
     * @param responseBody Response body (will be serialized)
     */
    default void storeSuccess(String idempotencyKey, Long userId, HttpStatus httpStatus, Object responseBody) {
        storeSuccess(idempotencyKey, userId, httpStatus, responseBody, null);
    }

    void storeSuccess(String idempotencyKey, Long userId, HttpStatus httpStatus, Object responseBody, String scope);

    /**
     * Store failed operation result.
     * 
     * @param idempotencyKey Client-provided UUID
     * @param userId Current authenticated user ID
     * @param httpStatus Response status code
     * @param errorMessage Error message
     */
    default void storeFailure(String idempotencyKey, Long userId, HttpStatus httpStatus, String errorMessage) {
        storeFailure(idempotencyKey, userId, httpStatus, errorMessage, null);
    }

    void storeFailure(String idempotencyKey, Long userId, HttpStatus httpStatus, String errorMessage, String scope);

    /**
     * Remove idempotency record (cleanup after transient errors).
     * 
     * @param idempotencyKey Client-provided UUID
     * @param userId Current authenticated user ID
     */
    default void remove(String idempotencyKey, Long userId) {
        remove(idempotencyKey, userId, null);
    }

    void remove(String idempotencyKey, Long userId, String scope);

    /**
     * Get the storage type description for logging.
     * 
     * @return Description like "Redis" or "In-Memory"
     */
    String getStorageType();
}
