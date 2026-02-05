package org.example.rentoza.booking.exception;

import org.example.rentoza.config.timezone.SerbiaTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for booking-related validation errors.
 * 
 * <p>Provides consistent, structured error responses for all booking validation failures.
 * 
 * <h2>Response Format</h2>
 * <pre>
 * {
 *   "error": true,
 *   "code": "BOOKING_IN_PAST",
 *   "message": "Cannot create a booking in the past",
 *   "field": "startTime",
 *   "details": "Requested: 2024-01-01T10:00, Current: 2024-12-15T14:30",
 *   "timestamp": "2024-12-15T14:30:00"
 * }
 * </pre>
 * 
 * @author Rentoza Platform Team
 * @since Phase 9.0 - Edge Case Hardening
 */
@RestControllerAdvice(basePackages = "org.example.rentoza.booking")
public class BookingValidationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BookingValidationExceptionHandler.class);

    /**
     * Handles BookingValidationException and returns a structured error response.
     * 
     * @param ex The validation exception
     * @return ResponseEntity with error details and 400 Bad Request status
     */
    @ExceptionHandler(BookingValidationException.class)
    public ResponseEntity<Map<String, Object>> handleBookingValidationException(BookingValidationException ex) {
        log.warn("Booking validation failed: {} - {} (field: {})", 
            ex.getErrorCode(), ex.getMessage(), ex.getField());
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", true);
        response.put("code", ex.getErrorCode());
        response.put("message", ex.getMessage());
        response.put("field", ex.getField());
        response.put("timestamp", SerbiaTimeZone.now().toString());
        
        if (ex.getDetails() != null) {
            response.put("details", ex.getDetails());
        }
        
        if (!ex.getContext().isEmpty()) {
            response.put("context", ex.getContext());
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles BookingConflictException for double-booking scenarios.
     */
    @ExceptionHandler(BookingConflictException.class)
    public ResponseEntity<Map<String, Object>> handleBookingConflictException(BookingConflictException ex) {
        log.warn("Booking conflict detected: {}", ex.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", true);
        response.put("code", "BOOKING_CONFLICT");
        response.put("message", ex.getMessage());
        response.put("timestamp", SerbiaTimeZone.now().toString());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * Handles PriceCalculationException for pricing mismatches.
     */
    @ExceptionHandler(PriceCalculationException.class)
    public ResponseEntity<Map<String, Object>> handlePriceCalculationException(PriceCalculationException ex) {
        log.error("Price calculation error: {}", ex.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", true);
        response.put("code", "PRICE_CALCULATION_ERROR");
        response.put("message", "Price calculation error - please refresh and try again");
        response.put("timestamp", SerbiaTimeZone.now().toString());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
}
