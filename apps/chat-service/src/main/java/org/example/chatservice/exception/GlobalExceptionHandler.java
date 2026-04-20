package org.example.chatservice.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.example.chatservice.exception.StorageUpstreamException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleConversationNotFound(ConversationNotFoundException ex) {
        return buildErrorResponse(ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException ex) {
        log.warn("[Security] Forbidden access attempt: {}", ex.getMessage());
        return buildErrorResponse(ex.getMessage(), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("[Security] Access denied: {}", ex.getMessage());
        return buildErrorResponse("Access denied: " + ex.getMessage(), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(MessagingNotAllowedException.class)
    public ResponseEntity<Map<String, Object>> handleMessagingNotAllowed(MessagingNotAllowedException ex) {
        return buildErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return buildErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );
        
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("errors", errors);
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(RateLimitExceededException ex) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        response.put("error", "Too Many Requests");
        response.put("message", ex.getMessage());
        response.put("retryAfterSeconds", ex.getRetryAfterSeconds());
        
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(response);
    }

    @ExceptionHandler(ContentModerationException.class)
    public ResponseEntity<Map<String, Object>> handleContentModeration(ContentModerationException ex) {
        log.info("Content moderation blocked message: {} (type: {})", 
                ex.getMessage(), ex.getViolationType());
        
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("error", "Bad Request");
        response.put("errorCode", "CONTENT_MODERATION");
        response.put("violationType", ex.getViolationType().getCode());
        response.put("message", ex.getMessage());
        response.put("userMessage", ex.getUserMessage());
        response.put("violations", ex.getViolations());
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        log.warn("Attachment upload rejected: file too large");

        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.put("error", "Payload Too Large");
        response.put("message", "Attachment exceeds the maximum allowed size");
        response.put("userMessage", "Fajl je prevelik. Maksimalna veličina je 10MB.");

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipartException(MultipartException ex) {
        String message = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";

        if (message.contains("size") || message.contains("too large") || message.contains("maxuploadsize")) {
            log.warn("Attachment upload rejected by multipart resolver due to size limit");

            Map<String, Object> response = new HashMap<>();
            response.put("timestamp", LocalDateTime.now());
            response.put("status", HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.put("error", "Payload Too Large");
            response.put("message", "Attachment exceeds the maximum allowed size");
            response.put("userMessage", "Fajl je prevelik. Maksimalna veličina je 10MB.");

            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
        }

        return buildErrorResponse("Neispravan upload fajla.", HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(StorageUpstreamException.class)
    public ResponseEntity<Map<String, Object>> handleStorageUpstream(StorageUpstreamException ex) {
        log.error("[Storage] Upstream failure: {}", ex.getMessage());
        return buildErrorResponse(
                "Usluga za fajlove trenutno nije dostupna. Pokušajte ponovo.",
                HttpStatus.BAD_GATEWAY);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return buildErrorResponse("An unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(String message, HttpStatus status) {
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", status.value());
        response.put("error", status.getReasonPhrase());
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }
}
