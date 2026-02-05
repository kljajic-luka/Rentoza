package org.example.rentoza.config;

import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.exception.UserOverlapException;
import org.example.rentoza.exception.BookingConflictException;
import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.security.ratelimit.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import jakarta.persistence.OptimisticLockException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.validation.FieldError;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle authorization failures.
     *
     * <p>Spring Security may throw either {@link AccessDeniedException} or
     * {@link AuthorizationDeniedException} (method security).
     *
     * <p>Returning 403 keeps API semantics correct and avoids masking auth issues as 500s.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<Map<String, Object>> handleAccessDenied(Exception ex) {
        log.warn("Access denied: {}", ex.getMessage());

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "Forbidden");
        body.put("message", "Access Denied");

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * Handle transient DB deadlocks / lock acquisition failures.
     * Enterprise-grade behavior: return a retryable status instead of a generic 500.
     */
    @ExceptionHandler({CannotAcquireLockException.class, DeadlockLoserDataAccessException.class})
    public ResponseEntity<Map<String, Object>> handleDatabaseDeadlock(Exception ex) {
        log.warn("Database lock/deadlock detected: {}", ex.getMessage());

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "Conflict");
        body.put("code", "DB_DEADLOCK");
        body.put("message", "Temporary concurrency issue. Please retry.");

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "1")
                .body(body);
    }

    /**
     * Handle optimistic locking failures gracefully.
     * 
     * <p><b>BUG-006:</b> When concurrent updates occur on the same entity (e.g., two users
     * editing the same booking), JPA throws OptimisticLockException. Instead of failing
     * with a 500, we return 409 Conflict with a clear retry message.
     * 
     * <p><b>Use cases:</b>
     * <ul>
     *   <li>Booking status updates (owner and renter editing simultaneously)</li>
     *   <li>Vehicle details updates</li>
     *   <li>User profile updates</li>
     *   <li>Damage claim resolutions</li>
     * </ul>
     * 
     * <p><b>Frontend handling:</b>
     * <ul>
     *   <li>Check for error.code === 'STALE_DATA'</li>
     *   <li>Refresh the entity and prompt user to resubmit changes</li>
     *   <li>Show user-friendly message: "Data has been modified by another user"</li>
     * </ul>
     * 
     * @param ex The optimistic locking exception
     * @return 409 Conflict with Retry-After header and Serbian message
     */
    @ExceptionHandler({
        OptimisticLockingFailureException.class, 
        ObjectOptimisticLockingFailureException.class,
        OptimisticLockException.class  // JPA-level exception
    })
    public ResponseEntity<Map<String, Object>> handleOptimisticLockException(Exception ex) {
        log.warn("Optimistic lock failure detected: {}", ex.getMessage());

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "Conflict");
        body.put("code", "STALE_DATA");
        body.put("message", "Podaci su izmenjeni od strane drugog korisnika. Molimo osvežite stranicu i pokušajte ponovo.");
        body.put("messageEn", "Data has been modified by another user. Please refresh and try again.");
        body.put("retryable", true);

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "0")
                .body(body);
    }

    /**
     * Handle all uncaught exceptions with sanitized error response.
     * 
     * <p><b>SECURITY (Issue 1.1):</b> Never expose internal details to clients:
     * <ul>
     *   <li>No SQL errors (e.g., "ORA-00942: table or view does not exist")</li>
     *   <li>No stack traces (e.g., "NullPointerException at BookingService:423")</li>
     *   <li>No internal paths (e.g., "/var/rentoza/config/secrets.yml")</li>
     * </ul>
     * 
     * <p>Response format:
     * <pre>
     * {
     *   "timestamp": "2026-01-31T12:00:00Z",
     *   "error": "Internal Server Error",
     *   "message": "Došlo je do greške. Molimo pokušajte ponovo.",
     *   "correlationId": "ERR-a1b2c3d4",
     *   "support": "Za pomoć, navedite ID greške: ERR-a1b2c3d4"
     * }
     * </pre>
     * 
     * <p>Full exception details are logged server-side with correlationId for debugging.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralError(Exception ex) {
        // Generate unique correlation ID for support tracking
        String correlationId = "ERR-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Log full exception with correlation ID (server-side only)
        log.error("[{}] Unhandled exception: type={}, message={}", 
                correlationId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
        
        // Build sanitized response (no internal details exposed)
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "Internal Server Error");
        body.put("message", "Došlo je do greške. Molimo pokušajte ponovo.");
        body.put("correlationId", correlationId);
        body.put("support", "Za pomoć, navedite ID greške: " + correlationId);
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * Handle database access exceptions with sanitized response.
     * 
     * <p><b>SECURITY:</b> Database errors often contain sensitive information:
     * <ul>
     *   <li>Table/column names</li>
     *   <li>SQL syntax details</li>
     *   <li>Connection strings</li>
     * </ul>
     * 
     * <p>Returns generic message with correlation ID for support.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDatabaseError(DataAccessException ex) {
        String correlationId = "DB-" + UUID.randomUUID().toString().substring(0, 8);
        
        log.error("[{}] Database error: type={}, message={}", 
                correlationId, ex.getClass().getSimpleName(), ex.getMessage(), ex);
        
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "Database Error");
        body.put("message", "Došlo je do greške sa bazom podataka. Molimo pokušajte ponovo.");
        body.put("correlationId", correlationId);
        body.put("support", "Za pomoć, navedite ID greške: " + correlationId);
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * Handle application-level validation exceptions.
     * 
     * <p>These exceptions contain user-friendly Serbian messages that ARE safe to expose.
     * Example: "Email adresa je već registrovana"
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(ValidationException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "Validation Error");
        body.put("message", ex.getMessage()); // Safe - these are user-facing messages
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handle resource not found exceptions.
     * Returns HTTP 404 with structured JSON response.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "Not Found");
        body.put("message", ex.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Handle user overlap booking exceptions (One Driver, One Car constraint).
     * 
     * Returns HTTP 409 Conflict with structured JSON response:
     * {
     *   "timestamp": "2025-11-27T12:00:00Z",
     *   "error": "Booking Overlap",
     *   "code": "USER_OVERLAP",
     *   "message": "Ne možete rezervisati dva vozila u isto vreme..."
     * }
     * 
     * Frontend Handling:
     * - Check for error.code === 'USER_OVERLAP' to show specific message
     * - Display user-friendly Serbian message from error.message
     * - Suggest alternative dates or show existing booking
     */
    @ExceptionHandler(UserOverlapException.class)
    public ResponseEntity<Map<String, Object>> handleUserOverlapException(UserOverlapException ex) {
        log.warn("User overlap booking attempt: {}", ex.getMessage());

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "Booking Overlap");
        body.put("code", "USER_OVERLAP");
        body.put("message", ex.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Handle booking conflict exceptions (car already booked).
     * 
     * Returns HTTP 409 Conflict with structured JSON response:
     * {
     *   "timestamp": "2025-11-27T12:00:00Z",
     *   "error": "Booking Conflict",
     *   "code": "CAR_UNAVAILABLE",
     *   "message": "This car is already booked for the selected dates..."
     * }
     */
    @ExceptionHandler(BookingConflictException.class)
    public ResponseEntity<Map<String, Object>> handleBookingConflictException(BookingConflictException ex) {
        log.warn("Booking conflict: {}", ex.getMessage());

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "Booking Conflict");
        body.put("code", "CAR_UNAVAILABLE");
        body.put("message", ex.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
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
        body.put("error", "RATE_LIMIT_EXCEEDED");
        body.put("message", "Previše zahteva. Pokušajte ponovo za " + ex.getRetryAfterSeconds() + " sekundi.");
        body.put("retryAfterSeconds", ex.getRetryAfterSeconds());

        // Add Retry-After header (HTTP standard)
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "Validation Error");
        body.put("message", "Input validation failed");

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        body.put("details", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handle authentication failures (invalid credentials).
     * 
     * SECURITY: Generic error message prevents email enumeration attacks.
     * Returns HTTP 401 Unauthorized with clean JSON response.
     * 
     * Response format:
     * {
     *   "timestamp": "2025-12-02T18:00:00Z",
     *   "error": "Unauthorized",
     *   "message": "Invalid email or password"
     * }
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "Unauthorized");
        body.put("message", "Invalid email or password");
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }
}