package org.example.rentoza.config;

import jakarta.servlet.http.HttpServletRequest;
import org.example.rentoza.monitoring.MissingResourceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Enterprise-grade exception handler for missing static resources.
 * 
 * <p>Handles scenarios where images/files are deleted from disk but still referenced in DB:
 * <ul>
 *   <li>Returns clean 404 JSON (no stack traces exposed)</li>
 *   <li>Logs at INFO level (expected behavior, not error)</li>
 *   <li>Supports placeholder image fallback</li>
 *   <li>Prevents security information leakage</li>
 * </ul>
 * 
 * <p><strong>Priority:</strong> Ordered.HIGHEST_PRECEDENCE + 1 (runs before GlobalExceptionHandler)
 * 
 * <p><strong>Use Cases:</strong>
 * <ul>
 *   <li>Car images deleted by owner but still in booking records</li>
 *   <li>Check-in photos removed during cleanup but cached in frontend</li>
 *   <li>User avatars deleted but profile still references old URL</li>
 * </ul>
 * 
 * <p><strong>Response Format:</strong>
 * <pre>
 * HTTP 404 NOT FOUND
 * {
 *   "timestamp": "2025-12-17T10:30:00Z",
 *   "error": "Resource Not Found",
 *   "code": "RESOURCE_DELETED",
 *   "message": "This image is no longer available",
 *   "path": "/car-images/47/photo.jpg"
 * }
 * </pre>
 * 
 * @see NoResourceFoundException Spring 6.2+ exception for missing static resources
 * @see NoHandlerFoundException Legacy exception for 404s (Spring <6.2)
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class StaticResourceExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(StaticResourceExceptionHandler.class);

    private final MissingResourceMetrics metrics;

    @Value("${app.resource-missing.return-placeholder:false}")
    private boolean returnPlaceholder;

    @Value("${app.resource-missing.fallback-image:/images/placeholder-car.svg}")
    private String fallbackImage;

    public StaticResourceExceptionHandler(MissingResourceMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Handle missing static resources (Spring 6.2+).
     * 
     * <p>Triggered when a static resource (image, CSS, JS) is requested but not found on disk.
     * Common causes:
     * <ul>
     *   <li>Image deleted from uploads folder</li>
     *   <li>File moved/renamed without DB update</li>
     *   <li>Cache pointing to non-existent resource</li>
     * </ul>
     * 
     * @param ex NoResourceFoundException containing resource path
     * @param request HTTP request for context (path, headers)
     * @return 404 with clean JSON error response (no stack trace)
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleMissingStaticResource(
            NoResourceFoundException ex,
            HttpServletRequest request) {

        String resourcePath = ex.getResourcePath();
        String requestURI = request.getRequestURI(); // Full path from request
        
        // Record metric for monitoring (use full URI for better tracking)
        metrics.recordMissingResource(requestURI);
        
        // Log at INFO level - this is expected behavior, not an error
        log.info("[StaticResource] Missing resource requested: {} from IP: {}", 
                resourcePath, 
                request.getRemoteAddr());

        // Check if this is an image request
        boolean isImage = isImageRequest(request);
        
        if (isImage && returnPlaceholder) {
            // Record placeholder usage
            metrics.recordPlaceholderServed(resourcePath);
            
            // Return redirect to placeholder image
            return ResponseEntity
                    .status(HttpStatus.TEMPORARY_REDIRECT)
                    .header("Location", fallbackImage)
                    .body(buildErrorResponse(
                            "RESOURCE_PLACEHOLDER",
                            "Image not found, redirecting to placeholder",
                            requestURI  // Use full URI
                    ));
        }

        // Return clean 404 JSON
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(buildErrorResponse(
                        "RESOURCE_DELETED",
                        isImage ? "This image is no longer available" : "Resource not found",
                        requestURI  // Use full URI
                ));
    }

    /**
     * Handle missing handlers/404s (legacy fallback for Spring <6.2).
     * 
     * <p>Catches cases where no handler is mapped to the request path.
     * In Spring 6.2+, static resources trigger NoResourceFoundException instead.
     * 
     * @param ex NoHandlerFoundException from Spring MVC
     * @param request HTTP request for context
     * @return 404 with clean JSON error response
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoHandlerFound(
            NoHandlerFoundException ex,
            HttpServletRequest request) {

        String requestUrl = ex.getRequestURL();
        
        log.info("[StaticResource] No handler found for: {} (method: {})", 
                requestUrl,
                ex.getHttpMethod());

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(buildErrorResponse(
                        "NO_HANDLER",
                        "The requested resource was not found",
                        requestUrl
                ));
    }

    /**
     * Check if the request is for an image file.
     * 
     * <p>Detects common image extensions to apply image-specific handling
     * (e.g., placeholder fallback).
     * 
     * @param request HTTP request to inspect
     * @return true if request path ends with image extension
     */
    private boolean isImageRequest(HttpServletRequest request) {
        String path = request.getRequestURI().toLowerCase();
        return path.endsWith(".jpg") || 
               path.endsWith(".jpeg") || 
               path.endsWith(".png") || 
               path.endsWith(".gif") || 
               path.endsWith(".webp") || 
               path.endsWith(".svg");
    }

    /**
     * Build standardized error response body.
     * 
     * <p>Response format:
     * <pre>
     * {
     *   "timestamp": "2025-12-17T10:30:00Z",
     *   "error": "Resource Not Found",
     *   "code": "RESOURCE_DELETED",
     *   "message": "This image is no longer available",
     *   "path": "/car-images/47/photo.jpg"
     * }
     * </pre>
     * 
     * @param code Error code for client-side handling (e.g., RESOURCE_DELETED)
     * @param message User-friendly error message
     * @param path Resource path that was not found
     * @return Map representing JSON error response
     */
    private Map<String, Object> buildErrorResponse(String code, String message, String path) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("error", "Resource Not Found");
        body.put("code", code);
        body.put("message", message);
        body.put("path", path);
        
        // DO NOT include stack trace - security best practice
        
        return body;
    }
}
