package org.example.rentoza.security.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.security.InternalServiceJwtUtil;
import org.example.rentoza.security.network.TrustedProxyIpExtractor;
import org.example.rentoza.security.supabase.SupabaseJwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Rate limiting filter using fixed-window counter algorithm.
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
 * - X-Forwarded-For validation prevents IP spoofing
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

    private final RateLimitService rateLimitService;
    private final AppProperties appProperties;
    private final SupabaseJwtUtil supabaseJwtUtil;
    private final InternalServiceJwtUtil internalServiceJwtUtil;
    private final TrustedProxyIpExtractor ipExtractor;

    // Paths that bypass rate limiting
    // Issue 1.3: Keep actuator exempt from rate limiting (health checks may be frequent)
    // but authentication is now enforced by SecurityConfig RBAC
    private static final List<String> EXCLUDED_PATHS = List.of(
            "/actuator/health",   // Health checks exempt (load balancer probes)
            "/actuator/info",     // Info endpoint (minimal overhead)
            "/h2-console/",       // H2 database console (dev only)
            "/uploads/"           // Static content
    );

    public RateLimitingFilter(RateLimitService rateLimitService,
                              AppProperties appProperties,
                              SupabaseJwtUtil supabaseJwtUtil,
                              InternalServiceJwtUtil internalServiceJwtUtil,
                              TrustedProxyIpExtractor ipExtractor) {
        this.rateLimitService = rateLimitService;
        this.appProperties = appProperties;
        this.supabaseJwtUtil = supabaseJwtUtil;
        this.internalServiceJwtUtil = internalServiceJwtUtil;
        this.ipExtractor = ipExtractor;
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
        String requestMethod = request.getMethod();

        // Extract rate limit configuration for this endpoint
        RateLimitConfig config = getRateLimitConfig(requestPath);

        // Determine rate limit key: JWT email (authenticated) or IP (unauthenticated)
        // Key is endpoint-scoped to prevent collateral throttling across endpoints
        String identityKey = getIdentityKey(request);
        String endpointBucket = normalizeEndpointBucket(requestPath);
        String rateLimitKey = identityKey + ":" + endpointBucket;

        // Determine endpoint criticality tier for fail-open vs fail-closed behavior
        RateLimitTier tier = resolveRateLimitTier(requestPath, requestMethod);

        // Check if request is allowed (tier-aware: CRITICAL endpoints fail-closed on Redis outage)
        boolean allowed = rateLimitService.allowRequest(rateLimitKey, config.limit, config.windowSeconds, tier);

        if (!allowed) {
            // Differentiate: rate limit exceeded vs. Redis unavailable on CRITICAL path
            // When Redis is down for a CRITICAL endpoint, the service returns false;
            // we check the remaining seconds to distinguish. If remaining == 0 and the
            // service returned false, it may be a Redis outage → respond 503.
            long retryAfterSeconds = rateLimitService.getRemainingSeconds(rateLimitKey);

            if (retryAfterSeconds == 0 && tier == RateLimitTier.CRITICAL) {
                // Redis is likely unavailable for a CRITICAL endpoint → 503
                log.warn("[RATE-LIMIT] Service unavailable for critical endpoint: {} {}", requestMethod, requestPath);
                response.setStatus(503);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":\"SERVICE_TEMPORARILY_UNAVAILABLE\"," +
                        "\"message\":\"Rate limiting service is unavailable. Please retry shortly.\"}");
                return;
            }

            log.warn("Rate limit exceeded: key={}, endpoint={}, limit={}/{} seconds",
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
     * Classify endpoint criticality for rate-limit failure behavior.
     *
     * <p>CRITICAL endpoints are fail-closed: if Redis is down, the request is blocked (503).
     * STANDARD endpoints are fail-open: if Redis is down, the request is allowed through.
     */
    static RateLimitTier resolveRateLimitTier(String path, String method) {
        // Auth endpoints are always critical regardless of method
        if (path.startsWith("/api/auth/login") ||
            path.startsWith("/api/auth/register") ||
            path.startsWith("/api/auth/refresh") ||
            path.startsWith("/api/auth/supabase/login") ||
            path.startsWith("/api/auth/supabase/register") ||
            path.startsWith("/api/auth/supabase/refresh")) {
            return RateLimitTier.CRITICAL;
        }
        // Payment endpoints are always critical
        if (path.startsWith("/api/payments/")) {
            return RateLimitTier.CRITICAL;
        }
        // M4: Webhook endpoints are critical — public, unauthenticated DoS vector
        if (path.startsWith("/api/webhooks/")) {
            return RateLimitTier.CRITICAL;
        }
        // Booking creation and reauthorization are critical
        if (path.startsWith("/api/bookings") && "POST".equalsIgnoreCase(method)) {
            return RateLimitTier.CRITICAL;
        }
        if (path.contains("/reauthorize")) {
            return RateLimitTier.CRITICAL;
        }
        return RateLimitTier.STANDARD;
    }

    /**
     * Determine if this filter should be skipped for specific paths
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        // Skip CORS preflight requests - let them pass through
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        
        String path = request.getRequestURI();

        // Bypass rate limiting for excluded paths
        for (String excludedPath : EXCLUDED_PATHS) {
            if (path.contains(excludedPath)) {
                return true;
            }
        }

        // Bypass rate limiting for internal service calls only if token is cryptographically valid
        String internalToken = request.getHeader("X-Internal-Service-Token");
        if (internalToken != null && !internalToken.isEmpty()) {
            if (internalServiceJwtUtil.validateServiceToken(internalToken)) {
                return true; // Only bypass if token is cryptographically valid
            }
            log.warn("[RATE-LIMIT] Invalid X-Internal-Service-Token from {}", request.getRemoteAddr());
            // Fall through to normal rate limiting — do NOT hard-block.
            // A spoofed header is not a reason to reject; just rate-limit normally.
        }

        return false;
    }

    /**
     * Get rate limit configuration for a specific endpoint.
     * 
     * Priority:
     * 1. Exact endpoint match (e.g., /api/auth/login)
     * 2. Glob-pattern match for path-variable endpoints (wildcard matches one path segment)
     * 3. Default configuration
     */
    private RateLimitConfig getRateLimitConfig(String requestPath) {
        // 1. Exact match (most specific)
        AppProperties.RateLimit.EndpointLimit endpointLimit = 
                appProperties.getRateLimit().getEndpoints().get(requestPath);

        if (endpointLimit != null) {
            return new RateLimitConfig(endpointLimit.getLimit(), endpointLimit.getWindowSeconds());
        }

        // 2. Prefix match for path-variable endpoints
        // Handles patterns like /api/bookings/{id}/check-in/host/photos
        for (Map.Entry<String, AppProperties.RateLimit.EndpointLimit> entry : appProperties.getRateLimit().getEndpoints().entrySet()) {
            String pattern = entry.getKey();
            if (pattern.contains("*")) {
                // Convert glob pattern to regex: * matches one path segment
                String regex = pattern.replace("*", "[^/]+");
                if (requestPath.matches(regex)) {
                    return new RateLimitConfig(entry.getValue().getLimit(), entry.getValue().getWindowSeconds());
                }
            }
        }

        // 3. Use default configuration
        return new RateLimitConfig(
                appProperties.getRateLimit().getDefaultLimit(),
                appProperties.getRateLimit().getDefaultWindowSeconds()
        );
    }

    /**
     * Extract identity key from request (user email or IP address).
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
    private String getIdentityKey(HttpServletRequest request) {
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
                String email = supabaseJwtUtil.getEmailFromToken(token);
                if (email != null && !email.isEmpty()) {
                    return "user:" + email;
                }
            } catch (Exception e) {
                // JWT parsing failed - fall back to IP-based limiting
                log.debug("Failed to extract email from JWT, using IP: {}", e.getMessage());
            }
        }

        // Fall back to IP-based limiting for unauthenticated requests
        String ipAddress = ipExtractor.extractClientIp(request);
        return "ip:" + ipAddress;
    }

    /**
     * Normalize the request path into an endpoint bucket for rate-limit key scoping.
     *
     * <p>Replaces numeric path segments with {@code *} so that
     * {@code /api/bookings/123} and {@code /api/bookings/456} share the same bucket.
     * This prevents per-resource key explosion while keeping endpoint isolation.
     *
     * @param requestPath the raw request URI
     * @return normalized bucket string (e.g. {@code /api/bookings/*})
     */
    static String normalizeEndpointBucket(String requestPath) {
        if (requestPath == null || requestPath.isEmpty()) return "/";
        // Replace numeric path segments (IDs) with wildcard
        return requestPath.replaceAll("/[0-9]+(?=/|$)", "/*");
    }

    /**
     * Rate limit configuration for a specific request
     */
    private record RateLimitConfig(int limit, int windowSeconds) {}
}
