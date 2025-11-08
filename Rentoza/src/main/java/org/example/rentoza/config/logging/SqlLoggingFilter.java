package org.example.rentoza.config.logging;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Request logging filter that adds correlation IDs and tracks SQL query count per request.
 * 
 * Features:
 * - Generates unique correlation ID for each request
 * - Adds correlation ID to MDC (Mapped Diagnostic Context) for log tracing
 * - Resets SQL query counter at start of each request
 * - Logs SQL query statistics at end of request
 * - Groups SQL logs by request for better debugging
 * 
 * Active in development profile for enhanced debugging.
 * 
 * @author Rentoza Development Team
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SqlLoggingFilter implements Filter {

    private static final String CORRELATION_ID_KEY = "correlationId";
    private static final String REQUEST_URI_KEY = "requestUri";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        if (!(request instanceof HttpServletRequest httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        // Generate correlation ID for this request
        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        
        // Add to MDC for logging context
        MDC.put(CORRELATION_ID_KEY, correlationId);
        MDC.put(REQUEST_URI_KEY, httpRequest.getRequestURI());

        // Reset SQL query counter for this request
        SqlLoggingInspector.resetQueryCounter();

        long startTime = System.currentTimeMillis();

        try {
            // Process the request
            chain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            long queryCount = SqlLoggingInspector.getQueryCount();

            // Log request summary with SQL stats (only in dev mode)
            if (isDevelopmentMode() && queryCount > 0) {
                logRequestSummary(httpRequest, duration, queryCount);
            }

            // Clean up MDC
            MDC.remove(CORRELATION_ID_KEY);
            MDC.remove(REQUEST_URI_KEY);
        }
    }

    /**
     * Log request summary with SQL query statistics.
     */
    private void logRequestSummary(HttpServletRequest request, long duration, long queryCount) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        
        StringBuilder summary = new StringBuilder();
        summary.append("\n╔════════════════════════════════════════════════════════════════════════════╗\n");
        summary.append(String.format("║ 📊 Request Summary: %s %s\n", method, uri));
        summary.append("╠════════════════════════════════════════════════════════════════════════════╣\n");
        summary.append(String.format("║ ⏱️  Duration: %dms\n", duration));
        summary.append(String.format("║ 🗄️  SQL Queries: %d\n", queryCount));
        
        // Performance warning if too many queries
        if (queryCount > 10) {
            summary.append("║ ⚠️  Warning: High query count detected (N+1 problem?)\n");
        }
        
        summary.append("╚════════════════════════════════════════════════════════════════════════════╝");
        
        log.info(summary.toString());
    }

    /**
     * Check if running in development mode.
     */
    private boolean isDevelopmentMode() {
        String profile = System.getProperty("spring.profiles.active");
        return profile != null && profile.contains("dev");
    }
}
