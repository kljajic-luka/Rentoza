package org.example.rentoza.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT authentication filter that validates Bearer tokens on each request.
 * Applies to all endpoints except explicitly public ones defined in SecurityConfig.
 * 
 * Bean Registration:
 * - Registered as @Bean in SecurityConfig (not @Component)
 * - This prevents duplicate registration and enables proper filter chain ordering
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private static final List<String> PUBLIC_ENDPOINT_PREFIXES = List.of(
            "/login/oauth2",
            "/oauth2",
            "/login",
            "/uploads"
    );

    public JwtAuthFilter(JwtUtil jwtUtil, UserDetailsService userDetailsService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String requestUri = request.getRequestURI();

        try {
            String token = extractToken(request);
            
            if (token != null) {
                // SECURITY: Validate token BEFORE loading UserDetails to prevent unnecessary DB queries
                // with invalid/expired tokens
                if (!jwtUtil.validateToken(token)) {
                    log.debug("JWT validation failed for request to: {}", requestUri);
                    filterChain.doFilter(request, response);
                    return;
                }

                String email = jwtUtil.getEmailFromToken(token);
                if (email == null || email.isBlank()) {
                    log.warn("JWT token missing email claim for request to: {}", requestUri);
                    filterChain.doFilter(request, response);
                    return;
                }

                // CRITICAL FIX: Always replace existing authentication when valid JWT is present
                // This ensures JWT takes precedence over OAuth2 session authentication
                // Required for OAuth2 + JWT hybrid: after OAuth2 login, the session may conta                // DefaultOidcUser, but subsequent API calls with JWT must use token-based auth
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);

                // SECURITY: Ensure loaded principal is JwtUserPrincipal (required for RLS)
                if (!(userDetails instanceof JwtUserPrincipal jwtPrincipal)) {
                    log.error("Invalid principal type: expected JwtUserPrincipal, got {}", 
                            userDetails.getClass().getSimpleName());
                    filterChain.doFilter(request, response);
                    return;
                }

                // SECURITY: Subject consistency check - verify JWT email matches loaded user
                if (!email.equalsIgnoreCase(jwtPrincipal.getUsername())) {
                    log.error("JWT subject mismatch: token email={}, loaded user={}, IP={}",
                            email, jwtPrincipal.getUsername(), request.getRemoteAddr());
                    filterChain.doFilter(request, response);
                    return;
                }

                // Set authentication in SecurityContext with JwtUserPrincipal
                // This enables services to access userId via CurrentUser or @AuthenticationPrincipal
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                                jwtPrincipal,
                                null,
                                jwtPrincipal.getAuthorities()
                        );
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);

                log.debug("JWT authentication established for user: {} on endpoint: {}",
                        email, requestUri);
            }
        } catch (ExpiredJwtException e) {
            log.debug("JWT token expired for request to {}: {}", requestUri, e.getMessage());
            // CRITICAL FIX: Return 401 immediately instead of continuing filter chain
            // This prevents Spring Security from attempting OAuth2 redirect
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"JWT token expired\"}");
            return; // Stop filter chain here
        } catch (SignatureException e) {
            log.warn("Invalid JWT signature for request to {} from IP {}: {}",
                    requestUri, request.getRemoteAddr(), e.getMessage());
            // Return 401 for invalid signatures (potential tampering)
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Invalid JWT signature\"}");
            return; // Stop filter chain here
        } catch (Exception e) {
            log.error("JWT validation error for request to {} from IP {}: {}",
                    requestUri, request.getRemoteAddr(), e.getMessage());
            // Return 401 for any other JWT errors
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"JWT validation failed\"}");
            return; // Stop filter chain here
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extract JWT token from Authorization header or access_token cookie.
     * Prioritizes header if present.
     */
    private String extractToken(HttpServletRequest request) {
        // 1. Check Authorization header
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }

        // 2. Check access_token cookie
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if ("access_token".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        // ENDPOINT SCOPING: This filter should NOT run on OAuth2 authentication endpoints
        // OAuth2 flow uses different authentication mechanisms (authorization code, OIDC)
        // JWT filter only applies to API endpoints that expect Bearer tokens
        //
        // Excluded paths:
        // - /login/oauth2/** (OAuth2 authorization flow)
        // - /oauth2/** (OAuth2 callbacks and redirects)
        // - /login (traditional form login - if enabled)
        //
        // All other authenticated endpoints (/api/**) SHOULD use JWT authentication
        boolean shouldSkip = PUBLIC_ENDPOINT_PREFIXES.stream().anyMatch(path::startsWith);

        if (shouldSkip) {
            log.trace("Skipping JWT filter for public endpoint: {}", path);
        }

        return shouldSkip;
    }
}
