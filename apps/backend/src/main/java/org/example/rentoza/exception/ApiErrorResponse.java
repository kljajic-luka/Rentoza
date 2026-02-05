package org.example.rentoza.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * Standardized API error response format.
 * 
 * <p>All API errors return this consistent structure for frontend consumption.
 * 
 * <h2>Response Format</h2>
 * <pre>
 * {
 *   "error": {
 *     "code": "BOOKING_CONFLICT",
 *     "message": "This vehicle is already booked for selected dates",
 *     "details": {
 *       "conflictingBookingId": 123,
 *       "availableFrom": "2026-02-10"
 *     },
 *     "timestamp": "2026-02-04T18:00:00Z",
 *     "requestId": "abc-123-def",
 *     "path": "/api/bookings"
 *   }
 * }
 * </pre>
 * 
 * <h2>Error Codes</h2>
 * <ul>
 *   <li><b>VALIDATION_ERROR</b> - 400 - Input validation failed</li>
 *   <li><b>UNAUTHORIZED</b> - 401 - Authentication required</li>
 *   <li><b>FORBIDDEN</b> - 403 - Permission denied</li>
 *   <li><b>NOT_FOUND</b> - 404 - Resource not found</li>
 *   <li><b>BOOKING_CONFLICT</b> - 409 - Booking date conflict</li>
 *   <li><b>USER_OVERLAP</b> - 409 - User has overlapping booking</li>
 *   <li><b>RATE_LIMIT_EXCEEDED</b> - 429 - Too many requests</li>
 *   <li><b>INTERNAL_ERROR</b> - 500 - Server error</li>
 *   <li><b>SERVICE_UNAVAILABLE</b> - 503 - External service down</li>
 * </ul>
 * 
 * @since Phase 5 - Reliability & Monitoring
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiErrorResponse {
    
    /**
     * The error wrapper object.
     */
    private ErrorBody error;
    
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorBody {
        
        /**
         * Machine-readable error code (e.g., "BOOKING_CONFLICT").
         * Frontend can use this for specific error handling logic.
         */
        private String code;
        
        /**
         * Human-readable error message in user's language.
         * Safe to display directly to users.
         */
        private String message;
        
        /**
         * Additional error details (optional).
         * May contain field validation errors, conflicting IDs, etc.
         */
        private Map<String, Object> details;
        
        /**
         * ISO-8601 timestamp when error occurred.
         */
        private String timestamp;
        
        /**
         * Unique request ID for support tracking.
         * Users should reference this when contacting support.
         */
        private String requestId;
        
        /**
         * The API path that was requested.
         */
        private String path;
        
        /**
         * Retry-After hint in seconds (for 429/503 responses).
         */
        private Integer retryAfter;
    }
    
    // ========== FACTORY METHODS ==========
    
    /**
     * Create a validation error response (400).
     */
    public static ApiErrorResponse validation(String message, Map<String, Object> fieldErrors, String requestId, String path) {
        return ApiErrorResponse.builder()
                .error(ErrorBody.builder()
                        .code("VALIDATION_ERROR")
                        .message(message)
                        .details(fieldErrors)
                        .timestamp(Instant.now().toString())
                        .requestId(requestId)
                        .path(path)
                        .build())
                .build();
    }
    
    /**
     * Create an unauthorized error response (401).
     */
    public static ApiErrorResponse unauthorized(String message, String requestId, String path) {
        return ApiErrorResponse.builder()
                .error(ErrorBody.builder()
                        .code("UNAUTHORIZED")
                        .message(message)
                        .timestamp(Instant.now().toString())
                        .requestId(requestId)
                        .path(path)
                        .build())
                .build();
    }
    
    /**
     * Create a forbidden error response (403).
     */
    public static ApiErrorResponse forbidden(String message, String requestId, String path) {
        return ApiErrorResponse.builder()
                .error(ErrorBody.builder()
                        .code("FORBIDDEN")
                        .message(message)
                        .timestamp(Instant.now().toString())
                        .requestId(requestId)
                        .path(path)
                        .build())
                .build();
    }
    
    /**
     * Create a not found error response (404).
     */
    public static ApiErrorResponse notFound(String message, String requestId, String path) {
        return ApiErrorResponse.builder()
                .error(ErrorBody.builder()
                        .code("NOT_FOUND")
                        .message(message)
                        .timestamp(Instant.now().toString())
                        .requestId(requestId)
                        .path(path)
                        .build())
                .build();
    }
    
    /**
     * Create a conflict error response (409).
     */
    public static ApiErrorResponse conflict(String code, String message, Map<String, Object> details, String requestId, String path) {
        return ApiErrorResponse.builder()
                .error(ErrorBody.builder()
                        .code(code)
                        .message(message)
                        .details(details)
                        .timestamp(Instant.now().toString())
                        .requestId(requestId)
                        .path(path)
                        .build())
                .build();
    }
    
    /**
     * Create a rate limit error response (429).
     */
    public static ApiErrorResponse rateLimited(String message, int retryAfterSeconds, String requestId, String path) {
        return ApiErrorResponse.builder()
                .error(ErrorBody.builder()
                        .code("RATE_LIMIT_EXCEEDED")
                        .message(message)
                        .timestamp(Instant.now().toString())
                        .requestId(requestId)
                        .path(path)
                        .retryAfter(retryAfterSeconds)
                        .build())
                .build();
    }
    
    /**
     * Create an internal server error response (500).
     */
    public static ApiErrorResponse internalError(String message, String requestId, String path) {
        return ApiErrorResponse.builder()
                .error(ErrorBody.builder()
                        .code("INTERNAL_ERROR")
                        .message(message)
                        .timestamp(Instant.now().toString())
                        .requestId(requestId)
                        .path(path)
                        .build())
                .build();
    }
    
    /**
     * Create a service unavailable error response (503).
     */
    public static ApiErrorResponse serviceUnavailable(String message, int retryAfterSeconds, String requestId, String path) {
        return ApiErrorResponse.builder()
                .error(ErrorBody.builder()
                        .code("SERVICE_UNAVAILABLE")
                        .message(message)
                        .timestamp(Instant.now().toString())
                        .requestId(requestId)
                        .path(path)
                        .retryAfter(retryAfterSeconds)
                        .build())
                .build();
    }
}
