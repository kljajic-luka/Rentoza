package org.example.rentoza.config;

import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.security.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket handshake interceptor for JWT authentication.
 * Extracts JWT token from cookies or Authorization header and validates it before allowing WebSocket connection.
 * 
 * This enables cookie-based authentication for WebSocket connections, which is critical for
 * the localStorage → HttpOnly cookie migration.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        
        String token = extractToken(request);

        if (token == null) {
            denyHandshake(response, "Missing JWT for WebSocket handshake");
            return false;
        }

        try {
            if (jwtUtil.validateToken(token)) {
                String email = jwtUtil.getEmailFromToken(token);
                String userId = jwtUtil.getUserIdFromToken(token);

                attributes.put("email", email);
                attributes.put("userId", userId);
                attributes.put("authenticated", true);

                log.debug("WebSocket handshake authenticated for user: {}", email);
                return true;
            }

            denyHandshake(response, "JWT failed validation for WebSocket handshake");
            return false;
        } catch (Exception e) {
            log.warn("WebSocket handshake failed - invalid token: {}", e.getMessage());
            denyHandshake(response, "Invalid JWT for WebSocket handshake");
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                              WebSocketHandler wsHandler, Exception exception) {
        // No-op
    }

    /**
     * Extract JWT token from cookies or Authorization header.
     * Prioritizes cookies for the migration to HttpOnly cookies.
     */
    private String extractToken(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            jakarta.servlet.http.HttpServletRequest httpRequest = servletRequest.getServletRequest();
            
            // 1. Check access_token cookie (preferred for cookie-based auth)
            Cookie[] cookies = httpRequest.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if ("access_token".equals(cookie.getName())) {
                        return cookie.getValue();
                    }
                }
            }
            
            // 2. Fallback to Authorization header (backwards compatibility)
            String authHeader = httpRequest.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                return authHeader.substring(7);
            }
        }
        
        return null;
    }

    private void denyHandshake(ServerHttpResponse response, String reason) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        log.debug("Blocking WebSocket handshake: {}", reason);
    }
}
