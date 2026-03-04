package org.example.rentoza.security;

import org.example.rentoza.config.AppProperties;
import org.example.rentoza.security.csrf.CustomCookieCsrfTokenRepository;
import org.example.rentoza.security.csrf.LoggingCsrfTokenRequestHandler;
import org.example.rentoza.security.ratelimit.RateLimitService;
import org.example.rentoza.security.ratelimit.RateLimitingFilter;
import org.example.rentoza.security.supabase.SupabaseJwtAuthFilter;
import org.example.rentoza.security.supabase.SupabaseJwtUtil;
import org.example.rentoza.security.supabase.SupabaseUserMappingRepository;
import org.example.rentoza.security.token.TokenDenylistService;
import org.example.rentoza.deprecated.jwt.JwtAuthenticationEntryPoint;
import org.example.rentoza.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.*;
import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final AppProperties appProperties;

    public SecurityConfig(JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
                          AppProperties appProperties) {
        this.jwtAuthenticationEntryPoint = jwtAuthenticationEntryPoint;
        this.appProperties = appProperties;
    }

    /**
     * Register RateLimitingFilter as a Spring-managed bean.
     *
     * Purpose: Token bucket rate limiting before authentication
     * Order: 1st in chain (fail-fast for abusive requests)
     *
     * Implementation Selection:
     * - If spring.data.redis.host is configured: RedisRateLimitService (distributed)
     * - Otherwise: InMemoryRateLimitService (single-instance)
     *
     * @param rateLimitService Rate limiting service (Redis or In-Memory)
     * @param appProperties Configuration for rate limits
     * @param supabaseJwtUtil JWT parser for extracting user email from tokens
     * @param internalServiceJwtUtil Validator for internal service tokens (rate-limit bypass guard)
     * @return Configured RateLimitingFilter instance
     */
    @Bean
    public RateLimitingFilter rateLimitingFilter(
            RateLimitService rateLimitService,
            AppProperties appProperties,
            SupabaseJwtUtil supabaseJwtUtil,
            InternalServiceJwtUtil internalServiceJwtUtil,
            org.example.rentoza.security.network.TrustedProxyIpExtractor ipExtractor) {
        return new RateLimitingFilter(rateLimitService, appProperties, supabaseJwtUtil, internalServiceJwtUtil, ipExtractor);
    }

    /**
     * Register ServiceAuthenticationFilter as a Spring-managed bean.
     * 
     * Purpose: Authenticate internal microservice-to-microservice requests
     * Order: 2nd in chain (after rate limiting, before JWT authentication)
     * 
     * @param internalServiceJwtUtil Internal service token validator
     * @return Configured ServiceAuthenticationFilter instance
     */
    @Bean
    public ServiceAuthenticationFilter serviceAuthenticationFilter(
            InternalServiceJwtUtil internalServiceJwtUtil) {
        return new ServiceAuthenticationFilter(internalServiceJwtUtil);
    }

    /**
     * Register SupabaseJwtAuthFilter as a Spring-managed bean.
     *
     * Purpose: Validate Supabase Auth JWT tokens and map to Rentoza users
     * Order: 3rd in chain (after internal service auth, before UsernamePasswordAuthenticationFilter)
     * 
     * @param supabaseJwtUtil Supabase JWT validator
     * @param userRepository Rentoza user repository
     * @param mappingRepository Supabase-Rentoza user mapping repository
     * @return Configured SupabaseJwtAuthFilter instance
     */
    @Bean
    public SupabaseJwtAuthFilter supabaseJwtAuthFilter(
            SupabaseJwtUtil supabaseJwtUtil,
            UserRepository userRepository,
            SupabaseUserMappingRepository mappingRepository,
            TokenDenylistService tokenDenylistService) {
        return new SupabaseJwtAuthFilter(supabaseJwtUtil, userRepository, mappingRepository, tokenDenylistService);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           RateLimitingFilter rateLimitingFilter,
                                           ServiceAuthenticationFilter serviceAuthenticationFilter,
                                           SupabaseJwtAuthFilter supabaseJwtAuthFilter) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // CSRF PROTECTION: Enabled for all cookie-based auth endpoints
                // SECURITY FIX: login/register/refresh now require XSRF token to prevent session fixation
                // Only logout and static uploads remain exempt (logout is idempotent, uploads use JWT header)
                .csrf(csrf -> csrf
                    .csrfTokenRepository(csrfTokenRepository())
                    .csrfTokenRequestHandler(loggingCsrfTokenRequestHandler())
                    .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                    .ignoringRequestMatchers(
                        "/api/auth/logout",  // Idempotent - safe without CSRF
                        "/api/auth/supabase/forgot-password", // Anonymous password recovery (rate-limited)
                        "/api/auth/supabase/reset-password",  // One-time token flow; CSRF adds no security value
                        "/api/auth/supabase/google/callback",  // OAuth callback from Supabase (PKCE)
                        "/api/auth/supabase/google/token-callback",  // OAuth token callback (implicit flow)
                        "/uploads/**",        // Static files - no state change
                        // Webhook endpoint: auth delegated to HMAC signature verification
                        // in ProviderEventService. Must be CSRF-exempt for external gateway callbacks.
                        "/api/webhooks/payment"
                    )
                )
                .authorizeHttpRequests(auth -> auth
                        // ============ ACTUATOR ENDPOINTS (Issue 1.3 - Security Hardening) ============
                        // Health endpoint is public for load balancer health checks
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Info endpoint is semi-public (minimal app info)
                        .requestMatchers("/actuator/info").permitAll()
                        // Prometheus metrics endpoint — exposes JVM, HikariCP, and HTTP metrics.
                        // W3: Require ADMIN role instead of permitAll to prevent reconnaissance.
                        // Monitoring systems should use a service account with ADMIN credentials.
                        .requestMatchers("/actuator/prometheus").hasRole("ADMIN")
                        // Sensitive actuator endpoints require ADMIN role
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        
                        // Public auth endpoints (local + OAuth2)
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/register/user",   // Phase 2: Enhanced user registration
                                "/api/auth/register/owner",  // Phase 2: Enhanced owner registration
                                "/api/auth/refresh",
                                "/api/auth/logout",
                                "/api/auth/google/**"
                        ).permitAll()
                        // Supabase auth endpoints
                        .requestMatchers(
                                "/api/auth/supabase/register",
                                "/api/auth/supabase/login",
                                "/api/auth/supabase/refresh",
                                "/api/auth/supabase/logout",
                                "/api/auth/supabase/confirm-email",  // Email verification callback
                                "/api/auth/supabase/forgot-password", // P0: Password recovery
                                "/api/auth/supabase/reset-password",  // P0: Password reset
                                "/api/auth/supabase/google/**"       // Google OAuth via Supabase
                        ).permitAll()
                        // Debug endpoints — @Profile("!prod") prevents bean registration in production.
                        // Defense-in-depth: require ADMIN even in dev (controller also has @PreAuthorize).
                        .requestMatchers("/api/auth/debug/**").hasRole("ADMIN")
                        // OAuth2 endpoints — handled by LegacyOAuth2SecurityConfig when
                        // legacy.oauth2.enabled=true; kept permitAll as fallback (404 when disabled)
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        // Public static resources (images, documents, uploads)
                        .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/car-images/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/check-in-photos/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/user-avatars/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/documents/**").permitAll()

                        // ============ PUBLIC CAR MARKETPLACE ============
                        // Car browsing, search, and availability (guest-accessible)
                        .requestMatchers(HttpMethod.GET,
                                "/api/cars",
                                "/api/cars/search",
                                "/api/cars/availability-search",
                                "/api/cars/features",
                                "/api/cars/makes"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/cars/{id}").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/cars/location/**").permitAll()

                        // ============ PUBLIC REVIEWS ============
                        .requestMatchers(HttpMethod.GET, "/api/reviews/car/**").permitAll()

                        // Calendar availability for booking UI (no PII exposure)
                        // IMPORTANT: Must come BEFORE /api/bookings/* internal service rule
                        .requestMatchers(HttpMethod.GET, "/api/bookings/car/*/public").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/availability/*").permitAll()
                        // FIX: Add car-specific availability endpoint (used by booking dialog calendar)
                        .requestMatchers(HttpMethod.GET, "/api/cars/*/availability").permitAll()

                        // ============ PUBLIC OWNER PROFILES ============
                        .requestMatchers(HttpMethod.GET, "/api/owners/*/public-profile").permitAll()

                        // ============ PUBLIC LOCATION SEARCH (Mapbox Geocoding) ============
                        // Location search must be public for guest users browsing cars/homepage
                        .requestMatchers(HttpMethod.GET, "/api/locations/**").permitAll()

                        // ============ PUBLIC HOMEPAGE STATS ============
                        .requestMatchers(HttpMethod.GET, "/api/public/home-stats").permitAll()

                        // User endpoints - must come before catch-all rules
                        .requestMatchers("/api/users/me").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/users/me").authenticated()
                        // Profile picture upload/delete endpoints
                        .requestMatchers(HttpMethod.POST, "/api/users/me/profile-picture").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/users/me/profile-picture").authenticated()
                        .requestMatchers("/api/users/profile", "/api/users/profile/details").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/users/profile").authenticated()
                        
                        // ============ ADMIN ENDPOINTS (Phase 1 Remediation) ============
                        // User moderation: ban/unban users, list banned users
                        // RBAC: Strict ROLE_ADMIN requirement
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        
                        // Bookings user endpoints - must come before internal service rules
                        .requestMatchers("/api/bookings/me").authenticated()
                        .requestMatchers("/api/bookings/pending").hasAnyRole("OWNER", "ADMIN")
                        .requestMatchers("/api/bookings/user/**").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/bookings/cancel/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/bookings").authenticated()
                        // Conversation view endpoint - accessible to authenticated users AND internal service
                        // @PreAuthorize in controller enforces RLS + service-to-service validation
                        .requestMatchers(HttpMethod.GET, "/api/bookings/*/conversation-view")
                            .hasAnyAuthority("ROLE_USER", "ROLE_OWNER", "ROLE_ADMIN", "INTERNAL_SERVICE")
                        // Internal service endpoints - only specific endpoints require INTERNAL_SERVICE authority
                        .requestMatchers(HttpMethod.GET, "/api/users/profile/*").hasAuthority("INTERNAL_SERVICE")
                        // Booking details - accessible to authenticated users (RLS enforced in controller)
                        .requestMatchers(HttpMethod.GET, "/api/bookings/*").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/bookings/debug/**").hasAuthority("INTERNAL_SERVICE")
                        .requestMatchers("/api/favorites/**").authenticated()
                        // Webhook endpoint: auth delegated to HMAC signature verification
                        // in ProviderEventService. Must be permitAll for external gateway callbacks.
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/payment").permitAll()
                        .anyRequest().authenticated()
                )
                .headers(h -> h
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
                        .contentSecurityPolicy(csp -> csp.policyDirectives(buildCspPolicy()))
                        .referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .xssProtection(Customizer.withDefaults())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000)) // 1 year
                        .contentTypeOptions(Customizer.withDefaults()) // X-Content-Type-Options: nosniff
                )
                // EXCEPTION HANDLING: Return JSON 401 for unauthenticated requests
                //
                // JwtAuthenticationEntryPoint returns clean JSON:
                //   {"error":"Unauthorized","message":"JWT expired or invalid"}
                // Frontend sees 401 -> triggers silent token refresh.
                //
                // NOTE: Legacy OAuth2 login (if enabled) runs in a separate SecurityFilterChain
                // at @Order(0) — see LegacyOAuth2SecurityConfig.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                // SESSION MANAGEMENT: STATELESS for JWT-based authentication
                // All API endpoints use Supabase JWT tokens — no server-side sessions.
                // Legacy OAuth2 (if enabled) runs in a separate filter chain (LegacyOAuth2SecurityConfig).
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // FILTER CHAIN ORDER (execution from top to bottom):
                // 1. RateLimitingFilter (fail fast on rate limit exceeded)
                // 2. ServiceAuthenticationFilter (internal service authentication)
                // 3. SupabaseJwtAuthFilter (Supabase Auth JWT validation)
                // 4. UsernamePasswordAuthenticationFilter (Spring Security default)
                //
                // CRITICAL: Using bean instances (not class references) to avoid IllegalArgumentException
                // "The Filter class X does not have a registered order"
                //
                // Correct ordering strategy:
                // - All custom filters anchored to UsernamePasswordAuthenticationFilter.class
                // - Spring preserves declaration order when filters share the same anchor
                // - ONLY use built-in Spring Security filters as anchors (e.g., UsernamePasswordAuthenticationFilter)
                // - NEVER use custom filter classes as anchors (e.g., JwtAuthFilter.class)
                //
                // Filter chain: RateLimit -> ServiceAuth -> SupabaseJwtAuth -> UsernamePassword
                .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(serviceAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(supabaseJwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter.class);

        SecurityFilterChain chain = http.build();
        logRateLimitConfiguration();
        logSecurityChainInitialization();
        return chain;
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new CustomCookieCsrfTokenRepository(appProperties);
    }

    @Bean
    public LoggingCsrfTokenRequestHandler loggingCsrfTokenRequestHandler() {
        return new LoggingCsrfTokenRequestHandler();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration c = new CorsConfiguration();

        // Use environment-based CORS origins
        String[] allowedOrigins = appProperties.getCors().getAllowedOriginsArray();
        c.setAllowedOrigins(Arrays.asList(allowedOrigins));

        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        
        // P0-2 FIX: Production CORS configuration with all required headers
        // Includes Phase 3 API optimization headers (idempotency, ETags) as these are
        // production features, not dev-only testing.
        c.setAllowedHeaders(List.of(
                "Authorization",       // OAuth2/JWT authentication
                "Content-Type",        // Request body type
                "Cache-Control",       // Cache directives
                "X-XSRF-TOKEN",        // CSRF protection (Angular)
                "X-CSRF-TOKEN",        // CSRF protection (alternative)
                "X-Idempotency-Key",   // Idempotency for POST/PUT requests
                "If-None-Match",       // ETag conditional GET support
                "If-Match"             // ETag conditional PUT support (optimistic locking)
        ));
        c.setExposedHeaders(List.of("ETag", "X-Request-Id"));  // Allow frontend to read ETag responses
        
        c.setAllowCredentials(true); // Required for cookies
        c.setMaxAge(3600L); // Cache preflight requests for 1 hour

        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**", c);
        return s;
    }

    /**
     * Build Content Security Policy directive dynamically based on allowed origins.
     * 
     * SECURITY HARDENING:
     * - Removed 'unsafe-eval' entirely (major XSS vector)
     * - Retained 'unsafe-inline' for styles only (Angular Material requirement)
     * - Scripts use strict allowlist (Google OAuth only)
     * - Added frame-ancestors to prevent clickjacking
     * - Added base-uri restriction to prevent base tag injection
     * - Added form-action restriction
     * 
     * TODO: Implement nonce-based CSP for scripts when Angular build pipeline supports it
     */
    private String buildCspPolicy() {
        // Split comma-separated origins safely
        String allowedOrigins = appProperties.getCors().getAllowedOrigins();
        String[] origins = allowedOrigins.split(",");

        // Join with spaces for CSP syntax
        String connectSrc = String.join(" ", origins);

        return "default-src 'self'; " +
                "img-src 'self' data: blob: https://images.unsplash.com https://*.googleusercontent.com; " +
                "script-src 'self' https://accounts.google.com https://apis.google.com; " +
                "style-src 'self' 'unsafe-inline'; " +  // Required for Angular Material
                "connect-src 'self' " + connectSrc + " wss: https://accounts.google.com; " +
                "frame-src 'self' https://accounts.google.com; " +
                "frame-ancestors 'self'; " +  // Clickjacking protection
                "base-uri 'self'; " +          // Base tag injection protection
                "form-action 'self';"          // Form submission restriction
                ;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    private void logRateLimitConfiguration() {
        if (appProperties.getRateLimit().isEnabled()) {
            log.info("✅ Rate limiting enabled: default={} requests/{} seconds", 
                    appProperties.getRateLimit().getDefaultLimit(),
                    appProperties.getRateLimit().getDefaultWindowSeconds());
            
            if (!appProperties.getRateLimit().getEndpoints().isEmpty()) {
                log.info("📊 Endpoint-specific rate limits configured: {} endpoints", 
                        appProperties.getRateLimit().getEndpoints().size());
            }
        } else {
            log.warn("⚠️ Rate limiting disabled globally");
        }
    }

    /**
     * Log security filter chain initialization for audit trail and diagnostics.
     * 
     * This provides visibility into the deterministic filter execution order,
     * which is critical for:
     * - Performance optimization (rate limiting before expensive JWT validation)
     * - Security auditing (understanding authentication flow)
     * - Debugging (verifying filter order matches security requirements)
     * 
     * Filter Chain Execution Order:
     * 1. RateLimitingFilter → Fail-fast for abusive IPs/users (no authentication cost)
     * 2. ServiceAuthenticationFilter → Authenticate internal microservice calls
     * 3. SupabaseJwtAuthFilter → Validate Supabase Auth JWT tokens
     * 4. UsernamePasswordAuthenticationFilter → Spring Security default (rarely used)
     */
    private void logSecurityChainInitialization() {
        log.info("Security Filter Chain initialized with custom filters in order:");
        log.info("   1. RateLimitingFilter - Token bucket rate limiting (IP/user-based)");
        log.info("   2. ServiceAuthenticationFilter - Internal microservice authentication");
        log.info("   3. SupabaseJwtAuthFilter - Supabase Auth JWT validation");
        log.info("   4. UsernamePasswordAuthenticationFilter - Spring Security default");
        log.info("   Stateless authentication model: Supabase JWT-first (legacy OAuth2 in separate chain if enabled)");
    }
}
