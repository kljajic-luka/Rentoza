package org.example.rentoza.config.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter that adds correlation/request IDs and user context to MDC for structured logging.
 * 
 * <h2>MDC Keys</h2>
 * <ul>
 *   <li><b>requestId</b> - Unique ID for this request (for log correlation)</li>
 *   <li><b>correlationId</b> - Client-provided correlation ID (X-Correlation-ID header)</li>
 *   <li><b>userId</b> - Authenticated user ID (added after auth)</li>
 *   <li><b>path</b> - Request path</li>
 *   <li><b>method</b> - HTTP method</li>
 * </ul>
 * 
 * <h2>Response Headers</h2>
 * <ul>
 *   <li><b>X-Request-ID</b> - Server-generated request ID for support reference</li>
 * </ul>
 * 
 * <h2>Usage in Logs</h2>
 * <pre>
 * 2026-02-04 18:00:00.000 [req:abc123] [user:42] INFO BookingService - Booking created
 * </pre>
 * 
 * @since Phase 5 - Reliability & Monitoring
 */
@Component("requestLoggingContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingContextFilter extends OncePerRequestFilter {
    
    public static final String REQUEST_ID_KEY = "requestId";
    public static final String CORRELATION_ID_KEY = "correlationId";
    public static final String USER_ID_KEY = "userId";
    public static final String PATH_KEY = "path";
    public static final String METHOD_KEY = "method";
    
    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // Generate unique request ID
            String requestId = generateRequestId();
            MDC.put(REQUEST_ID_KEY, requestId);
            
            // Use client-provided correlation ID if present, otherwise use request ID
            String correlationId = request.getHeader(CORRELATION_ID_HEADER);
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = requestId;
            }
            MDC.put(CORRELATION_ID_KEY, correlationId);
            
            // Add request context
            MDC.put(PATH_KEY, request.getRequestURI());
            MDC.put(METHOD_KEY, request.getMethod());
            
            // Add request ID to response header for client reference
            response.setHeader(REQUEST_ID_HEADER, requestId);
            
            // Store request ID as request attribute (for exception handler access)
            request.setAttribute(REQUEST_ID_KEY, requestId);
            
            filterChain.doFilter(request, response);
            
        } finally {
            // Clean up MDC to prevent memory leaks
            MDC.remove(REQUEST_ID_KEY);
            MDC.remove(CORRELATION_ID_KEY);
            MDC.remove(USER_ID_KEY);
            MDC.remove(PATH_KEY);
            MDC.remove(METHOD_KEY);
        }
    }
    
    /**
     * Generate a short, URL-safe request ID.
     * Format: "REQ-" + 8 hex characters (e.g., "REQ-a1b2c3d4")
     */
    private String generateRequestId() {
        return "REQ-" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    /**
     * Set the user ID in MDC after authentication.
     * Called from JWT auth filter after successful authentication.
     * 
     * @param userId The authenticated user's ID
     */
    public static void setUserId(Long userId) {
        if (userId != null) {
            MDC.put(USER_ID_KEY, userId.toString());
        }
    }
    
    /**
     * Get the current request ID from MDC.
     * 
     * @return The request ID or "unknown" if not set
     */
    public static String getCurrentRequestId() {
        String requestId = MDC.get(REQUEST_ID_KEY);
        return requestId != null ? requestId : "unknown";
    }
}
