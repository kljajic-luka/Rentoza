package org.example.chatservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.chatservice.repository.UserRepository;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Supabase JWT Authentication Filter for ES256 Tokens
 *
 * <p>Validates Supabase Auth JWTs and populates SecurityContext with user ID</p>
 *
 * <h3>Execution Flow:</h3>
 * <ol>
 *   <li>Extract JWT from Authorization header or cookie</li>
 *   <li>Validate token using SupabaseJwtUtil (ES256 signature verification)</li>
 *   <li>Check token denylist (reject logged-out tokens)</li>
 *   <li>Map Supabase UUID to Rentoza BIGINT user ID</li>
 *   <li>Create authentication token with principal = BIGINT as String (e.g., "123456")</li>
 *   <li>Store in SecurityContext</li>
 *   <li>Continue filter chain</li>
 * </ol>
 *
 * <h3>Token Sources (in order of precedence):</h3>
 * <ul>
 *   <li>Authorization header: "Bearer &lt;token&gt;"</li>
 *   <li>Cookie: "access_token=&lt;token&gt;"</li>
 * </ul>
 *
 * <h3>Hybrid Authentication:</h3>
 * <p>This filter runs BEFORE JwtAuthenticationFilter (legacy HS256)</p>
 * <p>If Supabase token validation fails, continues to next filter (might be internal service JWT)</p>
 * <p>No fallback to HS256 for user tokens - only internal service uses HS256</p>
 *
 * @see SupabaseJwtUtil
 * @see JwtAuthenticationFilter (internal service auth)
 * @author Rentoza Development Team
 * @since 2.0.0 (Supabase Migration)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SupabaseJwtAuthFilter extends OncePerRequestFilter {

    private final SupabaseJwtUtil supabaseJwtUtil;
    private final UserRepository userRepository;
    private final TokenDenylistService tokenDenylistService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String jwt = resolveToken(request);

        // No token found - continue without authentication
        if (jwt == null || jwt.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            log.debug("Processing Supabase JWT token (length={}, prefix={}...)",
                jwt.length(), jwt.substring(0, Math.min(10, jwt.length())));

            // Validate Supabase ES256 token
            if (supabaseJwtUtil.validateToken(jwt)) {

                // P1: Check JWT denylist (tokens invalidated on logout)
                if (tokenDenylistService.isTokenDenied(jwt)) {
                    log.warn("Denied (logged-out) JWT used for: {}", request.getRequestURI());
                    filterChain.doFilter(request, response);
                    return;
                }

                // Map UUID to BIGINT user ID
                Long rentozaUserId = supabaseJwtUtil.getRentozaUserId(jwt);

                if (rentozaUserId != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                    // Build authorities from user role in database
                    List<GrantedAuthority> authorities = new ArrayList<>();
                    try {
                        userRepository.findRoleByUserId(rentozaUserId).ifPresent(role -> {
                            if (role != null && !role.isBlank()) {
                                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                                // Also add without prefix for flexible checks
                                authorities.add(new SimpleGrantedAuthority(role));
                                log.debug("Mapped role '{}' to authorities for userId={}", role, rentozaUserId);
                            }
                        });
                    } catch (Exception e) {
                        log.warn("Failed to load role for userId={}: {}", rentozaUserId, e.getMessage());
                        // Continue with empty authorities — user can still access non-admin endpoints
                    }

                    // Create authentication token and populate SecurityContext
                    // CRITICAL: Store BIGINT user ID as Long (not String) to match SQL schema
                    UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                            rentozaUserId,  // Store Long directly (was: String.valueOf(rentozaUserId))
                            null,
                            authorities  // Populated from DB role (was: empty list)
                        );

                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    log.debug("Supabase authentication successful: userId={}", rentozaUserId);
                }

            } else {
                // Validation failed - log and continue
                // Don't set error response - let next filter handle (might be internal service JWT)
                log.debug("Supabase token validation failed, continuing to next filter");
            }

        } catch (Exception e) {
            // Log error but don't fail request - continue to next filter
            log.warn("Supabase authentication error: {} - Continuing to next filter", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Resolve JWT token from request.
     *
     * <p>Checks in order:</p>
     * <ol>
     *   <li>Authorization header: "Bearer &lt;token&gt;"</li>
     *   <li>Cookie: "access_token=&lt;token&gt;"</li>
     * </ol>
     *
     * <p>Query-param token (?token=) is intentionally NOT supported to prevent
     * JWT leakage into server logs, browser history, and Referer headers.</p>
     *
     * @param request HTTP request
     * @return JWT token string or null if not found
     */
    private String resolveToken(HttpServletRequest request) {
        // 1. Check Authorization header
        final String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }

        // 2. Check cookies
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("access_token".equals(cookie.getName())) {
                    String token = cookie.getValue();
                    return token != null ? token.trim() : null;
                }
            }
        }

        return null;
    }
}
