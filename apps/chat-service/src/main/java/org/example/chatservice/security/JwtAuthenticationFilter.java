package org.example.chatservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token";

    private final JwtTokenProvider jwtTokenProvider;
    private final InternalServiceJwtUtil internalServiceJwtUtil;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // Skip if authentication already set (e.g., by SupabaseJwtAuthFilter)
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        final String serviceToken = resolveInternalServiceToken(request);
        if (serviceToken != null && !serviceToken.isBlank()) {
            authenticateInternalService(serviceToken, request);
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = resolveUserToken(request);

        if (jwt == null || jwt.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // Log token details for debugging (NEVER log the full token)
            if (logger.isDebugEnabled()) {
                logger.debug("Processing JWT token: length=" + jwt.length() + ", startsWith=" + 
                        (jwt.length() > 5 ? jwt.substring(0, 5) + "..." : "too_short"));
            }
            
            if (jwtTokenProvider.validateToken(jwt)) {
                String userId = jwtTokenProvider.extractUserId(jwt);

                if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            new ArrayList<>()
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            logger.error("Cannot set user authentication: " + e.getMessage(), e);
        }

        filterChain.doFilter(request, response);
    }

    private void authenticateInternalService(String serviceToken, HttpServletRequest request) {
        if (!internalServiceJwtUtil.validateServiceToken(serviceToken)) {
            return;
        }

        String serviceName = internalServiceJwtUtil.getServiceNameFromToken(serviceToken);
        if (serviceName == null || SecurityContextHolder.getContext().getAuthentication() != null) {
            return;
        }

        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                serviceName,
                null,
                java.util.Collections.singletonList(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE"))
        );
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }

    private String resolveInternalServiceToken(HttpServletRequest request) {
        String serviceToken = request.getHeader(INTERNAL_SERVICE_TOKEN_HEADER);
        return serviceToken != null ? serviceToken.trim() : null;
    }

    private String resolveUserToken(HttpServletRequest request) {
        final String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }

        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if ("access_token".equals(cookie.getName())) {
                return cookie.getValue() != null ? cookie.getValue().trim() : null;
            }
        }

        return null;
    }
}
