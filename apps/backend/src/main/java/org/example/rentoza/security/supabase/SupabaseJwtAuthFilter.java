package org.example.rentoza.security.supabase;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.rentoza.security.CookieConstants;
import org.example.rentoza.security.JwtUserPrincipal;
import org.example.rentoza.security.token.TokenDenylistService;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.example.rentoza.user.trust.AccountTrustSnapshot;
import org.example.rentoza.user.trust.AccountTrustStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Supabase JWT authentication filter.
 * 
 * <p>Validates Supabase Auth JWT tokens from:
 * <ul>
 *   <li>Authorization: Bearer &lt;token&gt; header</li>
 *   <li>access_token cookie (HttpOnly)</li>
 * </ul>
 * 
 * <p>Flow:
 * <ol>
 *   <li>Extract JWT from header or cookie</li>
 *   <li>Validate token signature and expiration</li>
 *   <li>Extract Supabase UUID from token</li>
 *   <li>Look up Rentoza user via mapping table</li>
 *   <li>Set Spring Security authentication context</li>
 * </ol>
 * 
 * <p>Bean Registration:
 * Registered as @Bean in SecurityConfig (not @Component)
 * to enable proper filter chain ordering.
 * 
 * @since Phase 2 - Supabase Auth Migration
 */
public class SupabaseJwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SupabaseJwtAuthFilter.class);

    private final SupabaseJwtUtil supabaseJwtUtil;
    private final UserRepository userRepository;
    private final SupabaseUserMappingRepository mappingRepository;
    private final TokenDenylistService tokenDenylistService;
    private final AccountTrustStateService accountTrustStateService;

    // Endpoints that bypass JWT authentication
    private static final List<String> PUBLIC_ENDPOINT_PREFIXES = List.of(
            "/api/auth",        // Auth endpoints (login, register, refresh, logout)
            "/api/cars",        // Public car browsing
            "/api/reviews",     // Public reviews
            "/api/locations",   // Location search
            "/api/availability", // Availability check
            "/login",
            "/uploads",
            "/car-images",
            "/check-in-photos",
            "/user-avatars",
            "/documents"
            // Issue 1.3: Removed "/actuator" - now handled by SecurityConfig RBAC rules
            // /actuator/health and /actuator/info are public
            // /actuator/metrics, /actuator/env, etc. require ADMIN role
    );

    public SupabaseJwtAuthFilter(
            SupabaseJwtUtil supabaseJwtUtil,
            UserRepository userRepository,
            SupabaseUserMappingRepository mappingRepository,
            TokenDenylistService tokenDenylistService,
            AccountTrustStateService accountTrustStateService
    ) {
        this.supabaseJwtUtil = supabaseJwtUtil;
        this.userRepository = userRepository;
        this.mappingRepository = mappingRepository;
        this.tokenDenylistService = tokenDenylistService;
        this.accountTrustStateService = accountTrustStateService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String requestUri = request.getRequestURI();

        try {
            String token = extractToken(request);

            if (token == null) {
                // Enhanced debug logging for 401 diagnosis
                if (log.isDebugEnabled() && !isPublicEndpoint(requestUri)) {
                    log.debug("No token found for protected endpoint: {} | Cookies present: {} | Auth header: {}",
                            requestUri,
                            request.getCookies() != null ? request.getCookies().length : 0,
                            request.getHeader("Authorization") != null ? "present" : "absent");
                    logCookieDebug(request);
                }
                filterChain.doFilter(request, response);
                return;
            }

            if (token != null) {
                // Quick check: Is this a Supabase token?
                // Supabase tokens have issuer claim pointing to Supabase URL
                // Legacy Rentoza tokens do NOT - so we skip those
                if (!isSupabaseToken(token)) {
                    log.trace("Token is not a Supabase token (likely legacy JWT), skipping Supabase filter");
                    filterChain.doFilter(request, response);
                    return;
                }
                
                log.debug("Supabase token found for {}, validating...", requestUri);

                // Validate token signature and expiration
                if (!supabaseJwtUtil.validateToken(token)) {
                    log.debug("Supabase JWT validation failed for: {}", requestUri);
                    filterChain.doFilter(request, response);
                    return;
                }

                // P0: Check JWT denylist (tokens invalidated on logout)
                if (tokenDenylistService.isTokenDenied(token)) {
                    log.warn("Denied (logged-out) JWT used for: {}", requestUri);
                    filterChain.doFilter(request, response);
                    return;
                }

                // Check if token is for authenticated user (not anon)
                if (!supabaseJwtUtil.isAuthenticated(token)) {
                    log.debug("Supabase token is for anonymous user, skipping auth for: {}", requestUri);
                    filterChain.doFilter(request, response);
                    return;
                }

                // Extract Supabase UUID
                UUID supabaseUserId = supabaseJwtUtil.getSupabaseUserId(token);
                if (supabaseUserId == null) {
                    log.warn("Supabase JWT missing user ID for: {}", requestUri);
                    filterChain.doFilter(request, response);
                    return;
                }

                // Look up Rentoza user via mapping
                Optional<SupabaseUserMapping> mappingOpt = mappingRepository.findById(supabaseUserId);
                if (mappingOpt.isEmpty()) {
                    log.warn("No Rentoza user mapping found for Supabase ID: {}", supabaseUserId);
                    // User authenticated in Supabase but not linked to Rentoza user
                    // This could happen during registration flow
                    filterChain.doFilter(request, response);
                    return;
                }

                Long rentozaUserId = mappingOpt.get().getRentozaUserId();
                
                // Load Rentoza user
                Optional<User> userOpt = userRepository.findById(rentozaUserId);
                if (userOpt.isEmpty()) {
                    log.error("Rentoza user not found for ID: {} (mapping exists)", rentozaUserId);
                    filterChain.doFilter(request, response);
                    return;
                }

                User user = userOpt.get();
                Date tokenIssuedAt = supabaseJwtUtil.getIssuedAt(token);
                if (user.getPasswordChangedAt() != null
                        && tokenIssuedAt != null
                        && tokenIssuedAt.toInstant().isBefore(user.getPasswordChangedAt())) {
                    log.warn("Rejected stale Supabase JWT after password reset: user={}", user.getEmail());
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"SESSION_REVOKED\",\"message\":\"Please sign in again.\"}");
                    return;
                }

                AccountTrustSnapshot trustSnapshot = accountTrustStateService.snapshot(user);
                if (!trustSnapshot.canAuthenticate()) {
                    log.warn("Blocked user attempted access: {} state={}",
                            user.getEmail(), trustSnapshot.accountAccessState());
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"ACCOUNT_BLOCKED\",\"message\":\"Your account is not active.\"}");
                    return;
                }

                // Build Spring Security authentication
                List<String> roles = List.of(user.getRole().name());
                JwtUserPrincipal principal = JwtUserPrincipal.create(
                        user.getId(),
                        user.getEmail(),
                        roles
                );

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("Supabase auth successful: user={}, role={}", user.getEmail(), user.getRole());
            }

        } catch (Exception e) {
            log.error("Supabase JWT authentication error: {}", e.getMessage());
            // Don't block request - let it proceed unauthenticated
            // Security config will handle 401 for protected endpoints
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extract JWT from request.
     * Priority: Authorization header > access_token cookie
     */
    private String extractToken(HttpServletRequest request) {
        // Try Authorization header first
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        // Fall back to cookie
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (CookieConstants.ACCESS_TOKEN.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        
        // Public static resources
        if (requestUri.startsWith("/uploads/") || 
            requestUri.startsWith("/car-images/") ||
            requestUri.startsWith("/check-in-photos/") ||
            requestUri.startsWith("/user-avatars/") ||
            requestUri.startsWith("/documents/")) {
            return true;
        }

        return false;
    }

    /**
     * Quick check to determine if a token is a Supabase token.
     * 
     * <p>Supabase tokens have specific characteristics:
     * <ul>
     *   <li>Issuer (iss) points to Supabase project URL</li>
     *   <li>Has 'role' claim (authenticated/anon)</li>
     * </ul>
     * 
     * <p>Legacy Rentoza JWT tokens do NOT have these, so we can
     * differentiate and skip Supabase validation for legacy tokens.
     * 
     * @param token JWT token string
     * @return true if token appears to be a Supabase token
     */
    private boolean isSupabaseToken(String token) {
        try {
            // Parse token WITHOUT validation (just decode)
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }
            
            // Decode payload (second part)
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
            
            // Quick check: Supabase tokens have "supabase" in issuer
            // and have a "role" claim
            return payload.contains("\"iss\"") && 
                   payload.contains("supabase") &&
                   payload.contains("\"role\"");
                   
        } catch (Exception e) {
            // If we can't parse it, assume it's not a Supabase token
            log.trace("Could not parse token to check if Supabase: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Debug helper to log cookie information for diagnosing auth issues.
     */
    private void logCookieDebug(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            log.debug("  → No cookies in request (possible domain/secure mismatch)");
            log.debug("  → Request host: {} | Scheme: {} | Origin: {}",
                    request.getServerName(),
                    request.getScheme(),
                    request.getHeader("Origin"));
            return;
        }
        
        StringBuilder cookieNames = new StringBuilder();
        boolean hasAccessToken = false;
        for (Cookie cookie : cookies) {
            cookieNames.append(cookie.getName()).append(", ");
            if (CookieConstants.ACCESS_TOKEN.equals(cookie.getName())) {
                hasAccessToken = true;
            }
        }
        log.debug("  → Cookies received: [{}] | access_token present: {}",
                cookieNames.toString().replaceAll(", $", ""),
                hasAccessToken);
    }
    
    /**
     * Check if endpoint is public (for logging purposes).
     */
    private boolean isPublicEndpoint(String uri) {
        return PUBLIC_ENDPOINT_PREFIXES.stream().anyMatch(uri::startsWith);
    }
}
