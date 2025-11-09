package org.example.rentoza.auth.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.rentoza.auth.RefreshTokenServiceEnhanced;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.AuthProvider;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

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
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private static final String OAUTH2_PLACEHOLDER_PASSWORD = "OAUTH2_NO_PASSWORD_";

    @Value("${oauth2.redirect-uri:http://localhost:4200/auth/callback}")
    private String frontendRedirectUri;

    public OAuth2AuthenticationSuccessHandler(
            JwtUtil jwtUtil,
            RefreshTokenServiceEnhanced refreshTokenService,
            AppProperties appProperties,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.jwtUtil = jwtUtil;
        this.refreshTokenService = refreshTokenService;
        this.appProperties = appProperties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            // Extract user from OAuth2 authentication
            OAuth2UserPrincipal principal = resolvePrincipal(authentication.getPrincipal());
            User user = ensureUserProvisioned(principal);

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
            log.error("Error handling OAuth2 authentication success: {}", ex.getMessage());

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

    private OAuth2UserPrincipal resolvePrincipal(Object principalObj) {
        if (principalObj instanceof OAuth2UserPrincipal principal) {
            return principal;
        }

        if (principalObj instanceof DefaultOidcUser oidcUser) {
            Map<String, Object> attributes = oidcUser.getAttributes();
            OAuth2UserPrincipal principal = buildPrincipalFromAttributes(attributes);
            log.debug("Reconstructed transient user from OIDC attributes for email={}", principal.getEmail());
            return principal;
        }

        if (principalObj instanceof OAuth2User oAuth2User) {
            Map<String, Object> attributes = oAuth2User.getAttributes();
            OAuth2UserPrincipal principal = buildPrincipalFromAttributes(attributes);
            log.debug("Reconstructed transient user from OAuth2 attributes for email={}", principal.getEmail());
            return principal;
        }

        log.error("Unsupported principal type: {}", principalObj != null ? principalObj.getClass() : "null");
        throw new IllegalStateException("Unsupported OAuth2 principal type");
    }

    private OAuth2UserPrincipal buildPrincipalFromAttributes(Map<String, Object> attributes) {
        User tempUser = new User();

        String email = valueAsString(attributes.get("email"));
        if (email == null) {
            email = valueAsString(attributes.get("sub"));
        }

        String givenName = valueAsString(attributes.get("given_name"));
        if (givenName == null) {
            givenName = valueAsString(attributes.get("name"));
        }

        String familyName = valueAsString(attributes.get("family_name"));
        String picture = valueAsString(attributes.get("picture"));

        tempUser.setEmail(email != null ? email : "oidc-user-" + System.currentTimeMillis());
        tempUser.setFirstName(givenName != null ? givenName : "User");
        tempUser.setLastName(familyName != null ? familyName : "");
        tempUser.setAvatarUrl(picture);
        tempUser.setAuthProvider(AuthProvider.GOOGLE);
        tempUser.setRole(Role.USER);
        tempUser.setEnabled(true);
        tempUser.setLocked(false);

        if (tempUser.getLastName() == null || tempUser.getLastName().length() < 3) {
            tempUser.setLastName(User.GOOGLE_PLACEHOLDER_LAST_NAME);
        }

        return new OAuth2UserPrincipal(tempUser, attributes);
    }

    private String valueAsString(Object value) {
        return value != null ? value.toString() : null;
    }

    private User ensureUserProvisioned(OAuth2UserPrincipal principal) {
        User user = principal.getUser();
        String email = user != null ? user.getEmail() : null;

        if (email == null || email.isBlank()) {
            email = valueAsString(principal.getAttributes() != null
                    ? principal.getAttributes().get("email")
                    : null);
        }

        if (email == null || email.isBlank()) {
            throw new IllegalStateException("OAuth2 principal missing email attribute");
        }

        if (user != null && user.getId() != null) {
            return user;
        }

        // make these effectively final for lambda use
        final String resolvedEmail = email;
        final User resolvedUser = user;
        final Map<String, Object> attrs = principal.getAttributes();

        return userRepository.findByEmail(resolvedEmail)
                .orElseGet(() -> provisionNewUser(resolvedEmail, resolvedUser, attrs));
    }

    private User provisionNewUser(String email, User currentUser, Map<String, Object> attributes) {
        User newUser = currentUser != null ? currentUser : new User();
        newUser.setEmail(email);

        String givenName = firstNonBlank(
                currentUser != null ? currentUser.getFirstName() : null,
                valueAsString(getAttribute(attributes, "given_name")),
                valueAsString(getAttribute(attributes, "name")),
                "User"
        );

        String familyName = firstNonBlank(
                currentUser != null ? currentUser.getLastName() : null,
                valueAsString(getAttribute(attributes, "family_name")),
                ""
        );

        String picture = firstNonBlank(
                currentUser != null ? currentUser.getAvatarUrl() : null,
                valueAsString(getAttribute(attributes, "picture"))
        );

        newUser.setFirstName(givenName);
        newUser.setLastName(familyName);
        if (newUser.getLastName() == null || newUser.getLastName().length() < 3) {
            newUser.setLastName(User.GOOGLE_PLACEHOLDER_LAST_NAME);
            log.debug("Assigned Google placeholder last name for user: {}", email);
        }
        newUser.setAvatarUrl(picture);
        newUser.setAuthProvider(AuthProvider.GOOGLE);
        newUser.setRole(Role.USER);
        newUser.setEnabled(true);
        newUser.setLocked(false);

        if (newUser.getPassword() == null || newUser.getPassword().isBlank()) {
            newUser.setPassword(passwordEncoder.encode(OAUTH2_PLACEHOLDER_PASSWORD + UUID.randomUUID()));
        }

        if (attributes != null && attributes.get("sub") != null) {
            newUser.setGoogleId(valueAsString(attributes.get("sub")));
        }

        try {
            User savedUser = userRepository.save(newUser);
            log.info("Provisioned new OAuth2 user: {}", savedUser.getEmail());
            return savedUser;
        } catch (DataIntegrityViolationException ex) {
            log.warn("Race condition while provisioning OAuth2 user {}. Attempting to reuse existing record.", email);
            return userRepository.findByEmail(email).orElseThrow(() -> ex);
        }
    }

    private Object getAttribute(Map<String, Object> attributes, String key) {
        return attributes != null ? attributes.get(key) : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
