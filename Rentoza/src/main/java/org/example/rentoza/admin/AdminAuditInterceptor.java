package org.example.rentoza.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * Admin Audit Interceptor for centralized request/response logging.
 * 
 * <p>Automatically adds MDC context for all admin API calls:
 * <ul>
 *   <li>requestId: Unique UUID for request correlation</li>
 *   <li>path: Request URI for tracing</li>
 * </ul>
 * 
 * <p>Logs all admin API calls with method, path, and status on completion.
 * 
 * @see org.example.rentoza.config.WebMvcConfig For interceptor registration
 */
@Component
@Slf4j
public class AdminAuditInterceptor implements HandlerInterceptor {
    
    @Override
    public boolean preHandle(HttpServletRequest request, 
                           HttpServletResponse response, 
                           Object handler) {
        // Generate correlation ID for distributed tracing
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("requestId", requestId);
        MDC.put("path", request.getRequestURI());
        
        // Add request ID to response header for client correlation
        response.setHeader("X-Request-ID", requestId);
        
        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, 
                               HttpServletResponse response, 
                               Object handler, Exception ex) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        int status = response.getStatus();
        
        if (ex != null) {
            log.warn("[Admin API] {} {} → {} (error: {})", 
                method, path, status, ex.getMessage());
        } else {
            log.info("[Admin API] {} {} → {}", method, path, status);
        }
        
        MDC.clear();
    }
}
