package org.example.rentoza.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom authentication entry point that returns JSON 401 responses instead of OAuth2 redirects.
 * 
 * CRITICAL: This prevents Spring Security from redirecting to Google OAuth2 when JWT authentication fails.
 * 
 * Without this, when a JWT expires or is invalid, Spring's default OAuth2 entry point would:
 * 1. Send HTTP 302 redirect to https://accounts.google.com/o/oauth2/v2/auth
 * 2. Cause CORS errors in the frontend (cross-origin redirect blocked)
 * 3. Break the frontend's token refresh flow
 * 
 * With this entry point:
 * - All unauthenticated requests to /api/** return clean 401 JSON responses
 * - Frontend can properly handle 401 and trigger silent token refresh
 * - OAuth2 login flow remains intact for /oauth2/** and /login/oauth2/** endpoints
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationEntryPoint.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, 
                        HttpServletResponse response,
                        AuthenticationException authException) throws IOException, ServletException {
        
        String requestUri = request.getRequestURI();
        String method = request.getMethod();
        
        log.debug("Authentication failed for {} {}: {}", method, requestUri, authException.getMessage());
        
        // Return JSON 401 response instead of OAuth2 redirect
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Unauthorized");
        errorResponse.put("message", "Authentication required. JWT expired or invalid.");
        errorResponse.put("path", requestUri);
        errorResponse.put("status", 401);
        errorResponse.put("timestamp", Instant.now().toString());
        
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
