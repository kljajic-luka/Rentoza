package org.example.rentoza.security;

import org.example.rentoza.auth.oauth2.CookieOAuth2AuthorizationRequestRepository;
import org.example.rentoza.auth.oauth2.CustomAuthorizationRequestResolver;
import org.example.rentoza.auth.oauth2.CustomOAuth2UserService;
import org.example.rentoza.auth.oauth2.OAuth2AuthenticationSuccessHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

import jakarta.annotation.PostConstruct;

/**
 * Legacy Spring Security OAuth2 login configuration.
 *
 * <p>This separate filter chain handles the OAuth2 authorization/callback flow
 * ({@code /oauth2/**}, {@code /login/oauth2/**}) and is only active when
 * {@code legacy.oauth2.enabled=true}.
 *
 * <p><b>Migration status:</b> Frontend has migrated to Supabase Google OAuth
 * ({@code authService.loginWithSupabaseGoogle()}). The legacy Spring OAuth2
 * flow is retained behind a feature flag for safety during transition.
 *
 * <p><b>Retirement plan:</b>
 * <ol>
 *   <li>Phase 2: Feature-flag isolation (this class) — dev disabled, prod enabled</li>
 *   <li>Phase 3: Set {@code legacy.oauth2.enabled=false} in production after confirming zero traffic</li>
 *   <li>Phase 4: Delete legacy OAuth2 classes entirely</li>
 * </ol>
 */
@Configuration
@ConditionalOnProperty(name = "legacy.oauth2.enabled", havingValue = "true")
public class LegacyOAuth2SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(LegacyOAuth2SecurityConfig.class);

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oauth2SuccessHandler;
    private final CustomAuthorizationRequestResolver customAuthorizationRequestResolver;
    private final CookieOAuth2AuthorizationRequestRepository cookieOAuth2AuthorizationRequestRepository;
    private final ClientRegistrationRepository clientRegistrationRepository;

    public LegacyOAuth2SecurityConfig(
            CustomOAuth2UserService customOAuth2UserService,
            OAuth2AuthenticationSuccessHandler oauth2SuccessHandler,
            CustomAuthorizationRequestResolver customAuthorizationRequestResolver,
            CookieOAuth2AuthorizationRequestRepository cookieOAuth2AuthorizationRequestRepository,
            ClientRegistrationRepository clientRegistrationRepository) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.oauth2SuccessHandler = oauth2SuccessHandler;
        this.customAuthorizationRequestResolver = customAuthorizationRequestResolver;
        this.cookieOAuth2AuthorizationRequestRepository = cookieOAuth2AuthorizationRequestRepository;
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    @PostConstruct
    void logLegacyOAuth2Active() {
        log.warn("Legacy Spring OAuth2 login chain ACTIVE (legacy.oauth2.enabled=true)");
        log.warn("This will be retired once Supabase Google OAuth is confirmed stable in production");

        ClientRegistration google = clientRegistrationRepository.findByRegistrationId("google");
        if (google != null) {
            log.info("Legacy OAuth2 provider configured: google (client-id={}...)",
                    google.getClientId().substring(0, Math.min(8, google.getClientId().length())));
        } else {
            log.warn("Google OAuth2 client registration not found — legacy flow non-functional");
        }
    }

    /**
     * Security filter chain for legacy Spring OAuth2 login endpoints.
     *
     * <p>Handles:
     * <ul>
     *   <li>{@code /oauth2/authorization/google} — redirects user to Google</li>
     *   <li>{@code /login/oauth2/code/google} — handles Google's callback</li>
     * </ul>
     *
     * <p>This chain runs at {@code @Order(0)}, before the primary Supabase JWT chain.
     * Only requests matching the security matcher are handled here.
     *
     * @param http HttpSecurity builder (scoped to this chain)
     * @param corsConfigurationSource Shared CORS configuration from SecurityConfig
     * @return SecurityFilterChain for OAuth2 paths only
     */
    @Bean
    @Order(0)
    public SecurityFilterChain legacyOAuth2FilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource) throws Exception {

        log.info("Initializing legacy OAuth2 filter chain for /oauth2/** and /login/oauth2/**");

        http
            .securityMatcher("/oauth2/**", "/login/oauth2/**")
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            // OAuth2 redirect flow uses state parameter for CSRF protection — no token needed
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .oauth2Login(oauth2 -> oauth2
                .authorizationEndpoint(authorization -> authorization
                    .authorizationRequestResolver(customAuthorizationRequestResolver)
                    .authorizationRequestRepository(cookieOAuth2AuthorizationRequestRepository)
                )
                .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                .successHandler(oauth2SuccessHandler)
            );

        return http.build();
    }
}
