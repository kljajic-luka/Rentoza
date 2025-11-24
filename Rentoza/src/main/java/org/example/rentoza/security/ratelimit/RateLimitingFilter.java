package org.example.rentoza.security.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Rate limiting filter using token bucket algorithm.
 * 
 * Execution Order:
 * 1. CORS filter
 * 2. → RateLimitingFilter (this filter) ← YOU ARE HERE
 * 3. ServiceAuthenticationFilter (internal service auth)
 * 4. JwtAuthFilter (JWT authentication)
 * 5. Controller method
 * 
 * Strategy:
 * - IP-based limiting for unauthenticated requests (login, register, public APIs)
 * - User-based limiting for authenticated requests (JWT email)
 * - Endpoint-specific limits configured via AppProperties
 * - Excluded paths bypass rate limiting (OAuth2, internal, static content)
 * 
 * Security:
 * - Fails fast before expensive authentication operations
 * - Prevents brute-force attacks on /api/auth/login and /api/auth/register
 * - Protects refresh token endpoint from spamming
 * - Prevents public API scraping/DoS
 * - Thread-safe atomic operations via InMemoryRateLimitService
 * 
 * Redis Migration:
 * - Current implementation uses in-memory storage (ConcurrentHashMap)
 * - For distributed deployments, replace InMemoryRateLimitService with RedisRateLimitService
 * - Redis ensures consistent rate limits across multiple application instances
 * 
 * Bean Registration:
 * - Registered as @Bean in SecurityConfig (not @Component)
 * - This prevents duplicate registration and enables proper filter chain ordering
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private final InMemoryRateLimitService rateLimitService;
    private final AppProperties appProperties;
    private final JwtUtil jwtUtil;

    // Paths that bypass rate limiting
    private static final List<String> EXCLUDED_PATHS = List.of(
            "/oauth2/",           // OAuth2 authorization endpoints
            "/login/oauth2/",     // OAuth2 login endpoints
            "/actuator/",         // Health checks and metrics
            "/h2-console/",       // H2 database console (dev only)
            "/uploads/"           // Static content
    );

    public RateLimitingFilter(InMemoryRateLimitService rateLimitService,
                              AppProperties appProperties,
                              JwtUtil jwtUtil) {
        this.rateLimitService = rateLimitService;
        this.appProperties = appProperties;
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        
        // Skip rate limiting if disabled globally
        if (!appProperties.getRateLimit().isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestPath = request.getRequestURI();

        // Extract rate limit configuration for this endpoint
        RateLimitConfig config = getRateLimitConfig(requestPath);

        // Determine rate limit key: JWT email (authenticated) or IP (unauthenticated)
        String rateLimitKey = getRateLimitKey(request);

        // Check if request is allowed
        boolean allowed = rateLimitService.allowRequest(rateLimitKey, config.limit, config.windowSeconds);

        if (!allowed) {
            // Rate limit exceeded - throw exception (handled by GlobalExceptionHandler)
            long retryAfterSeconds = rateLimitService.getRemainingSeconds(rateLimitKey);
            
            log.warn("🚫 Rate limit exceeded: key={}, endpoint={}, limit={}/{} seconds", 
                    rateLimitKey, requestPath, config.limit, config.windowSeconds);
            
            throw new RateLimitExceededException(
                    "Rate limit exceeded for " + requestPath,
                    requestPath,
                    (int) retryAfterSeconds
            );
        }

        // Request allowed - continue filter chain
        filterChain.doFilter(request, response);
    }

    /**
     * Determine if this filter should be skipped for specific paths
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();

        // Bypass rate limiting for excluded paths
        for (String excludedPath : EXCLUDED_PATHS) {
            if (path.contains(excludedPath)) {
                return true;
            }
        }

        // Bypass rate limiting for internal service calls
        String internalToken = request.getHeader("X-Internal-Service-Token");
        if (internalToken != null && !internalToken.isEmpty()) {
            return true;
        }

        return false;
    }

    /**
     * Get rate limit configuration for a specific endpoint.
     * 
     * Priority:
     * 1. Exact endpoint match (e.g., /api/auth/login)
     * 2. Default configuration
     */
    private RateLimitConfig getRateLimitConfig(String requestPath) {
        AppProperties.RateLimit.EndpointLimit endpointLimit = 
                appProperties.getRateLimit().getEndpoints().get(requestPath);

        if (endpointLimit != null) {
            return new RateLimitConfig(endpointLimit.getLimit(), endpointLimit.getWindowSeconds());
        }

        // Use default configuration
        return new RateLimitConfig(
                appProperties.getRateLimit().getDefaultLimit(),
                appProperties.getRateLimit().getDefaultWindowSeconds()
        );
    }

    /**
     * Extract rate limit key from request.
     * 
     * Strategy:
     * - For authenticated requests: Use JWT email (user-based limiting)
     * - For unauthenticated requests: Use IP address (IP-based limiting)
     * 
     * Security:
     * - JWT email prevents user from bypassing limits by changing IP
     * - IP address prevents brute-force on login/register endpoints
     * - Falls back to IP if JWT parsing fails (graceful degradation)
     */
    private String getRateLimitKey(HttpServletRequest request) {
        // Try to extract JWT email for authenticated requests
        String authHeader = request.getHeader("Authorization");
        String token = null;

        // 1. Try Authorization header
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } 
        // 2. Try access_token cookie
        else if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if ("access_token".equals(cookie.getName())) {
                    token = cookie.getValue();
                    break;
                }
            }
        }

        if (token != null) {
            try {
                String email = jwtUtil.getEmailFromToken(token);
                if (email != null && !email.isEmpty()) {
                    return "user:" + email;
                }
            } catch (Exception e) {
                // JWT parsing failed - fall back to IP-based limiting
                log.debug("Failed to extract email from JWT, using IP: {}", e.getMessage());
            }
        }

        // Fall back to IP-based limiting for unauthenticated requests
        String ipAddress = extractIpAddress(request);
        return "ip:" + ipAddress;
    }

    /**
     * Extract client IP address with X-Forwarded-For support.
     * 
     * PRODUCTION: If behind load balancer/proxy, use X-Forwarded-For header
     * SECURITY: Validate X-Forwarded-For to prevent IP spoofing
     */
    private String extractIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For: client, proxy1, proxy2
            // Take first IP (original client)
            return xForwardedFor.split(",")[0].trim();
        }

        // Direct connection (no proxy)
        return request.getRemoteAddr();
    }

    /**
     * Rate limit configuration for a specific request
     */
    private record RateLimitConfig(int limit, int windowSeconds) {}
}
