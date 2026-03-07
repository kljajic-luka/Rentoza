package org.example.rentoza.config;

import jakarta.servlet.http.Cookie;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.security.CookieConstants;
import org.example.rentoza.security.supabase.SupabaseJwtUtil;
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
 * Extracts JWT token from Authorization header or cookies and validates it before allowing WebSocket connection.
 * 
 * SECURITY: Token extraction priority is Header → Cookie (consistent with REST API).
 * This enables both explicit token auth and cookie-based authentication for WebSocket connections.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private final SupabaseJwtUtil jwtUtil;

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
                String userId = jwtUtil.getSupabaseUserId(token) != null
                        ? jwtUtil.getSupabaseUserId(token).toString()
                        : null;

                if (userId == null || userId.isBlank()) {
                    denyHandshake(response, "JWT missing user ID for WebSocket handshake");
                    return false;
                }

                attributes.put("email", email);
                attributes.put("userId", userId);
                attributes.put("authenticated", true);
                attributes.put("PRINCIPAL", new StompPrincipal(userId));

                log.debug("WebSocket handshake authenticated for userId: {}", userId);
                return true;
            }

            denyHandshake(response, "JWT failed validation for WebSocket handshake");
            return false;
        } catch (Exception e) {
            // SECURITY: Do not leak implementation details
            log.warn("WebSocket handshake failed - invalid token");
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
     * Extract JWT token from Authorization header or cookies.
     * 
     * SECURITY FIX: Priority is now Header → Cookie (consistent with JwtAuthFilter).
     * This prevents cookie-based token from overriding explicit Authorization header.
     */
    private String extractToken(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            jakarta.servlet.http.HttpServletRequest httpRequest = servletRequest.getServletRequest();
            
            // 1. Check Authorization header FIRST (preferred for explicit token auth)
            String authHeader = httpRequest.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                return authHeader.substring(7);
            }
            
            // 2. Fallback to access_token cookie (browser-based connections)
            Cookie[] cookies = httpRequest.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (CookieConstants.ACCESS_TOKEN.equals(cookie.getName())) {
                        return cookie.getValue();
                    }
                }
            }
        }
        
        return null;
    }

    private void denyHandshake(ServerHttpResponse response, String reason) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        log.debug("Blocking WebSocket handshake: {}", reason);
    }
}
