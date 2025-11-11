package org.example.rentoza.config;

import org.example.rentoza.security.ratelimit.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneralError(Exception ex) {
        Map<String, String> body = new HashMap<>();
        body.put("error", "Server error");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(io.jsonwebtoken.JwtException.class)
    public ResponseEntity<Map<String, String>> handleJwtError(io.jsonwebtoken.JwtException ex) {
        Map<String, String> body = new HashMap<>();
        body.put("error", "Invalid or expired token");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * Handle rate limit exceeded exceptions.
     * 
     * Returns HTTP 429 (Too Many Requests) with structured JSON response:
     * {
     *   "timestamp": "2025-11-11T15:00:00Z",
     *   "error": "Too many requests",
     *   "message": "Rate limit exceeded for /api/auth/login",
     *   "retryAfterSeconds": 60
     * }
     * 
     * Security:
     * - Minimal error disclosure (no internal implementation details)
     * - Includes Retry-After hint for client backoff strategies
     * - Logs violation with endpoint and retry-after for monitoring
     * 
     * Frontend Handling:
     * - Frontend should catch HTTP 429 and display user-friendly message
     * - Implement exponential backoff using retryAfterSeconds
     * - Show countdown timer or disable submit button
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(RateLimitExceededException ex) {
        log.warn("🚫 Rate limit exceeded: endpoint={}, retryAfter={}s", 
                ex.getEndpoint(), ex.getRetryAfterSeconds());

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "Too many requests");
        body.put("message", ex.getMessage());
        body.put("retryAfterSeconds", ex.getRetryAfterSeconds());

        // Add Retry-After header (HTTP standard)
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }
}