package org.example.rentoza.deprecated.jwt;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
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
import org.example.rentoza.security.JwtUserPrincipal;
import org.example.rentoza.security.CookieConstants;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * JWT authentication filter that validates Bearer tokens on each request.
 * Applies to all endpoints except explicitly public ones defined in SecurityConfig.
 * 
 * Bean Registration:
 * - Registered as @Bean in SecurityConfig (not @Component)
 * - This prevents duplicate registration and enables proper filter chain ordering
 * 
 * SECURITY: Uses CookieConstants for consistent cookie name references.
 */
@Deprecated(since = "2.1.0", forRemoval = true)
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    
    // Endpoints that should ALWAYS skip JWT authentication (truly public)
    // NOTE: /api/auth includes both legacy (/api/auth/**) and Supabase (/api/auth/supabase/**)
    private static final List<String> PUBLIC_ENDPOINT_PREFIXES = List.of(
            "/api/auth",    // Auth endpoints - includes /api/auth/supabase/** (prevents dual JWT validation)
            "/login/oauth2",
            "/oauth2",
            "/login",
            "/uploads",
            "/actuator",     // Health checks
            "/api/public"
    );
    
    // Specific GET endpoints that are public (exact paths or patterns)
    // These are public for browsing by guests
    private static final List<String> PUBLIC_GET_PATHS = List.of(
            "/api/cars",                    // List all cars
            "/api/cars/search",             // Search cars
            "/api/cars/availability-search",// Availability search
            "/api/cars/features",           // Car features list
            "/api/cars/makes",              // Car makes list
            "/api/cars/location",           // Location-based search
            "/api/reviews/car"              // Public reviews for a car
    );
    
    // Patterns for dynamic public GET endpoints (e.g., /api/cars/{id} but NOT /api/cars/owner/{email})
    private static final java.util.regex.Pattern PUBLIC_CAR_DETAIL_PATTERN = 
            java.util.regex.Pattern.compile("^/api/cars/\\d+$");  // Only numeric IDs are public

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
                log.debug("Token found for {}, validating...", requestUri);
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
                                token, // Store raw token as credentials for downstream services
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
            // SECURITY: Do not leak implementation details in error messages
            log.warn("Invalid JWT signature for request to {} from IP {}",
                    requestUri, request.getRemoteAddr());
            // Return 401 for invalid signatures (potential tampering)
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Authentication failed\"}");
            return; // Stop filter chain here
        } catch (Exception e) {
            // SECURITY: Do not leak implementation details in error messages
            log.error("JWT validation error for request to {} from IP {}",
                    requestUri, request.getRemoteAddr());
            // Return 401 for any other JWT errors
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Authentication failed\"}");
            return; // Stop filter chain here
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extract JWT token from Authorization header or access_token cookie.
     * Prioritizes header if present (consistent with REST API pattern).
     */
    private String extractToken(HttpServletRequest request) {
        // 1. Check Authorization header first (preferred for API calls)
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            log.debug("Token extracted from Authorization header");
            return bearerToken.substring(7);
        }

        // 2. Fallback to access_token cookie (for browser-based requests)
        if (request.getCookies() != null) {
            log.debug("Checking cookies for access token. Cookie count: {}", request.getCookies().length);
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                log.trace("Cookie found: {} (length: {})",
                    cookie.getName(),
                    cookie.getValue() != null ? cookie.getValue().length() : 0);
                if (CookieConstants.ACCESS_TOKEN.equals(cookie.getName())) {
                    log.debug("Access token cookie found");
                    return cookie.getValue();
                }
            }
            log.debug("No access token cookie found among {} cookies", request.getCookies().length);
        } else {
            log.debug("No cookies present in request");
        }

        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip CORS preflight requests - let them pass through
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        
        String path = request.getRequestURI();
        String method = request.getMethod();

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
        
        // Check fully public endpoints (all methods)
        boolean isFullyPublic = PUBLIC_ENDPOINT_PREFIXES.stream().anyMatch(path::startsWith);
        if (isFullyPublic) {
            log.trace("Skipping JWT filter for fully public endpoint: {}", path);
            return true;
        }
        
        // Check specific public GET endpoints (exact match or startsWith for sub-paths)
        if ("GET".equalsIgnoreCase(method)) {
            // Check exact path matches
            for (String publicPath : PUBLIC_GET_PATHS) {
                if (path.equals(publicPath) || path.startsWith(publicPath + "/")) {
                    // Special case: /api/cars/owner/* is NOT public (requires auth)
                    if (path.startsWith("/api/cars/owner/")) {
                        log.debug("Running JWT filter for owner-specific endpoint: {}", path);
                        return false;
                    }
                    log.trace("Skipping JWT filter for public GET endpoint: {}", path);
                    return true;
                }
            }
            
            // Check pattern: /api/cars/{numericId} is public (car detail page)
            if (PUBLIC_CAR_DETAIL_PATTERN.matcher(path).matches()) {
                log.trace("Skipping JWT filter for public car detail: {}", path);
                return true;
            }
        }
        
        // All other endpoints require JWT authentication
        log.debug("Running JWT filter for protected {} request to: {}", method, path);
        return false;
    }
}
