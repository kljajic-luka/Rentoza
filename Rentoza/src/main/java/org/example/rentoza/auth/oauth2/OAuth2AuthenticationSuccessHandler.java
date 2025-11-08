package org.example.rentoza.auth.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.rentoza.auth.RefreshTokenServiceEnhanced;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.Duration;

/**
 * Handles successful OAuth2 authentication.
 *
 * Responsibilities:
 * 1. Extract authenticated user from OAuth2 principal
 * 2. Generate JWT access token (same format as local login)
 * 3. Generate refresh token with IP/user-agent fingerprinting
 * 4. Set refresh token as HttpOnly cookie
 * 5. Redirect to Angular app with access token in URL parameter
 */
@Component
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2AuthenticationSuccessHandler.class);
    private static final String REFRESH_COOKIE = "rentoza_refresh";

    private final JwtUtil jwtUtil;
    private final RefreshTokenServiceEnhanced refreshTokenService;
    private final AppProperties appProperties;

    @Value("${oauth2.redirect-uri:http://localhost:4200/auth/callback}")
    private String frontendRedirectUri;

    public OAuth2AuthenticationSuccessHandler(
            JwtUtil jwtUtil,
            RefreshTokenServiceEnhanced refreshTokenService,
            AppProperties appProperties) {
        this.jwtUtil = jwtUtil;
        this.refreshTokenService = refreshTokenService;
        this.appProperties = appProperties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            // Extract user from OAuth2 authentication
            OAuth2UserPrincipal principal = (OAuth2UserPrincipal) authentication.getPrincipal();
            User user = principal.getUser();

            log.info("OAuth2 authentication successful for user: email={}, provider={}",
                    user.getEmail(), user.getAuthProvider());

            // Generate JWT access token (same format as local login)
            String accessToken = jwtUtil.generateToken(user.getEmail(), user.getRole().name(), user.getId());

            // Generate refresh token with IP/UserAgent fingerprinting
            String ipAddress = RefreshTokenServiceEnhanced.extractIpAddress(request);
            String userAgent = RefreshTokenServiceEnhanced.extractUserAgent(request);
            String refreshToken = refreshTokenService.issue(user.getEmail(), ipAddress, userAgent);

            // Set refresh token as HttpOnly cookie
            ResponseCookie cookie = createRefreshTokenCookie(refreshToken);
            response.addHeader("Set-Cookie", cookie.toString());

            // Build redirect URL with access token
            // Angular will read this token from URL and store it
            String redirectUrl = UriComponentsBuilder.fromUriString(frontendRedirectUri)
                    .queryParam("token", accessToken)
                    .build()
                    .toUriString();

            log.debug("Redirecting to Angular app: {}", frontendRedirectUri);
            getRedirectStrategy().sendRedirect(request, response, redirectUrl);

        } catch (Exception ex) {
            log.error("Error handling OAuth2 authentication success", ex);

            // Redirect to Angular with error parameter
            String errorRedirectUrl = UriComponentsBuilder.fromUriString(frontendRedirectUri)
                    .queryParam("error", "authentication_failed")
                    .build()
                    .toUriString();

            getRedirectStrategy().sendRedirect(request, response, errorRedirectUrl);
        }
    }

    /**
     * Create a standardized refresh token cookie with environment-based security settings
     */
    private ResponseCookie createRefreshTokenCookie(String token) {
        return ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/api/auth/refresh")
                .domain(appProperties.getCookie().getDomain())
                .sameSite(appProperties.getCookie().getSameSite())
                .maxAge(Duration.ofDays(14))
                .build();
    }
}
