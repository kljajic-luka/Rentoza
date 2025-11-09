package org.example.rentoza.security;

import org.example.rentoza.auth.oauth2.CustomOAuth2UserService;
import org.example.rentoza.auth.oauth2.OAuth2AuthenticationSuccessHandler;
import org.example.rentoza.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.*;
import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthFilter jwtAuthFilter;
    private final ServiceAuthenticationFilter serviceAuthenticationFilter;
    private final AppProperties appProperties;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oauth2SuccessHandler;
    private final ClientRegistrationRepository clientRegistrationRepository;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          ServiceAuthenticationFilter serviceAuthenticationFilter,
                          AppProperties appProperties,
                          CustomOAuth2UserService customOAuth2UserService,
                          OAuth2AuthenticationSuccessHandler oauth2SuccessHandler,
                          ClientRegistrationRepository clientRegistrationRepository) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.serviceAuthenticationFilter = serviceAuthenticationFilter;
        this.appProperties = appProperties;
        this.customOAuth2UserService = customOAuth2UserService;
        this.oauth2SuccessHandler = oauth2SuccessHandler;
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // Public auth endpoints (local + OAuth2)
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/refresh",
                                "/api/auth/logout",
                                "/api/auth/google/**",
                                "/api/cars/**",
                                "/api/reviews/car/**"
                        ).permitAll()
                        // OAuth2 endpoints - required for Google login flow
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/bookings/car/**").permitAll()
                        // User endpoints - must come before catch-all rules
                        .requestMatchers("/api/users/me").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/users/me").authenticated()
                        .requestMatchers("/api/users/profile", "/api/users/profile/details").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/users/profile").authenticated()
                        // Bookings user endpoints - must come before internal service rules
                        .requestMatchers("/api/bookings/me").authenticated()
                        .requestMatchers("/api/bookings/user/**").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/bookings/cancel/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/bookings").authenticated()
                        // Internal service endpoints - only specific endpoints require INTERNAL_SERVICE authority
                        .requestMatchers(HttpMethod.GET, "/api/users/profile/*").hasAuthority("INTERNAL_SERVICE")
                        .requestMatchers(HttpMethod.GET, "/api/bookings/*").hasAuthority("INTERNAL_SERVICE")
                        .requestMatchers(HttpMethod.GET, "/api/bookings/debug/**").hasAuthority("INTERNAL_SERVICE")
                        .requestMatchers("/api/favorites/**").authenticated()
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
                // SESSION MANAGEMENT: STATELESS for token-based authentication
                //
                // CRITICAL: This enforces a stateless, JWT-first authentication model
                //
                // Why STATELESS:
                // - All API endpoints use JWT Bearer tokens (no server-side sessions)
                // - OAuth2 login is only used for initial user provisioning
                // - After OAuth2 success, frontend receives JWT and uses it exclusively
                // - JwtAuthFilter ALWAYS replaces any OAuth2 session auth with JWT auth
                // - Prevents session fixation attacks and reduces server memory overhead
                //
                // OAuth2 Flow with STATELESS:
                // 1. User clicks "Login with Google" -> OAuth2 authorization flow starts
                // 2. OAuth2 may use minimal transient state during OIDC handshake
                // 3. OAuth2AuthenticationSuccessHandler provisions user and generates JWT
                // 4. Frontend receives JWT in redirect URL parameter
                // 5. All subsequent API calls use JWT in Authorization header
                // 6. No persistent session is maintained after OAuth2 redirect
                //
                // Defense in Depth:
                // - Even if OAuth2 creates temporary session, JwtAuthFilter replaces it
                // - FavoriteController has fallback to handle DefaultOidcUser gracefully
                // - JWT validation happens on every request (stateless verification)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Configure OAuth2 login
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService)
                        )
                        .successHandler(oauth2SuccessHandler)
                )
                // Add ServiceAuthenticationFilter before JwtAuthFilter
                .addFilterBefore(serviceAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        SecurityFilterChain chain = http.build();
        logOAuth2Configuration();
        return chain;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration c = new CorsConfiguration();

        // Use environment-based CORS origins
        String[] allowedOrigins = appProperties.getCors().getAllowedOriginsArray();
        c.setAllowedOrigins(Arrays.asList(allowedOrigins));

        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Cache-Control", "X-CSRF-TOKEN"));
        c.setAllowCredentials(true); // Required for cookies
        c.setMaxAge(3600L); // Cache preflight requests for 1 hour

        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/**", c);
        return s;
    }

    /**
     * Build Content Security Policy directive dynamically based on allowed origins
     */
    private String buildCspPolicy() {
        // Split comma-separated origins safely
        String allowedOrigins = appProperties.getCors().getAllowedOrigins();
        String[] origins = allowedOrigins.split(",");

        // Join with spaces for CSP syntax
        String connectSrc = String.join(" ", origins);

        return "default-src 'self'; " +
                "img-src 'self' data: blob: https://images.unsplash.com; " +
                "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://accounts.google.com https://apis.google.com; " +
                "style-src 'self' 'unsafe-inline'; " +
                "connect-src 'self' " + connectSrc + ";";
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    private void logOAuth2Configuration() {
        if (clientRegistrationRepository == null) {
            log.warn("⚠️ No OAuth2 client registrations available.");
            return;
        }

        ClientRegistration google = clientRegistrationRepository.findByRegistrationId("google");
        if (google != null) {
            log.info("✅ OAuth2 login enabled for provider: {}", google.getRegistrationId());
        } else {
            log.warn("⚠️ Google OAuth2 client registration not found. OAuth2 login disabled.");
        }
    }
}
