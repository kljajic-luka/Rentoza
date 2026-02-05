package org.example.rentoza.auth.oauth2;

import org.example.rentoza.user.AuthProvider;
import org.example.rentoza.user.RegistrationStatus;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Custom OAuth2 user service that processes Google user information.
 *
 * STATELESS ARCHITECTURE:
 * - No HttpSession usage (prevents JSESSIONID creation)
 * - OAuth2 mode (login/register) is extracted from OAuth2 state parameter
 * - Mode is embedded by CustomAuthorizationRequestResolver
 *
 * Supports both LOGIN and REGISTER modes:
 *
 * LOGIN mode (default):
 * 1. New user -> create with GOOGLE provider
 * 2. Existing GOOGLE user -> update profile if changed
 * 3. Existing LOCAL user -> throw exception (email already registered with password)
 *
 * REGISTER mode (explicit registration):
 * 1. New user -> create with GOOGLE provider
 * 2. Existing GOOGLE user -> throw exception (already registered)
 * 3. Existing LOCAL user -> throw exception (email already registered with password)
 */
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private static final Logger log = LoggerFactory.getLogger(CustomOAuth2UserService.class);
    private static final String OAUTH2_PLACEHOLDER_PASSWORD = "OAUTH2_NO_PASSWORD_";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OidcUserService oidcUserService = new OidcUserService();

    public CustomOAuth2UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oauth2User;

        try {
            oauth2User = delegateToProperService(userRequest);
        } catch (OAuth2AuthenticationException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Error loading OAuth2 user: {}", ex.getMessage());
            throw new OAuth2AuthenticationException(
                new OAuth2Error("oauth2_user_load_error", "Unable to load user from provider", null),
                ex
            );
        }

        try {
            return processOAuth2User(userRequest, oauth2User);
        } catch (Exception ex) {
            log.error("Error processing OAuth2 user: {}", ex.getMessage());
            throw new OAuth2AuthenticationException(
                new OAuth2Error("oauth2_processing_error", ex.getMessage(), null),
                ex
            );
        }
    }

    /**
     * Process OAuth2 user information from Google and create or update user record.
     * Mode is extracted from OAuth2 state parameter (stateless - no session).
     */
    private OAuth2User processOAuth2User(OAuth2UserRequest userRequest, OAuth2User oauth2User) {
        // Determine if this is a registration or login flow
        // STATELESS: Extract mode from OAuth2 state parameter (embedded by CustomAuthorizationRequestResolver)
        OAuth2Mode mode = getOAuth2ModeFromRequest(userRequest);
        log.debug("Processing OAuth2 user in {} mode", mode);

        // Extract user info from Google response
        String email = oauth2User.getAttribute("email");
        String googleId = oauth2User.getAttribute("sub"); // Google's unique user ID
        String givenName = oauth2User.getAttribute("given_name");
        String familyName = oauth2User.getAttribute("family_name");
        String picture = oauth2User.getAttribute("picture");
        Boolean emailVerified = oauth2User.getAttribute("email_verified");

        // Validate required fields
        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException(
                new OAuth2Error("missing_email", "Email not provided by OAuth2 provider", null)
            );
        }

        if (googleId == null || googleId.isBlank()) {
            throw new OAuth2AuthenticationException(
                new OAuth2Error("missing_user_id", "User ID not provided by OAuth2 provider", null)
            );
        }

        // Google requires email verification - block unverified emails
        if (emailVerified == null || !emailVerified) {
            log.warn("OAuth2 attempt with unverified email: {}", email);
            throw new OAuth2AuthenticationException(
                new OAuth2Error(
                    "unverified_email",
                    "Please verify your email address with Google before signing in",
                    null
                )
            );
        }

        // Check if user already exists
        User user = userRepository.findByEmail(email).orElse(null);

        if (user != null) {
            // User exists - handle based on mode and provider
            if (user.getAuthProvider() == AuthProvider.LOCAL) {
                // Email already registered with local authentication
                log.warn("OAuth2 attempt for email already registered locally: {}", email);
                throw new OAuth2AuthenticationException(
                    new OAuth2Error(
                        "email_already_registered",
                        "This email is already registered with password authentication. Please log in using your password.",
                        null
                    )
                );
            }

            // User exists with GOOGLE provider
            if (mode == OAuth2Mode.REGISTER) {
                // Registration mode - user already exists, reject
                log.warn("Registration attempt for already existing Google user: {}", email);
                throw new OAuth2AuthenticationException(
                    new OAuth2Error(
                        "user_already_exists",
                        "An account with this email already exists. Please log in instead.",
                        null
                    )
                );
            }

            // Login mode - update profile if changed
            return updateExistingGoogleUser(user, givenName, familyName, picture, oauth2User);
        } else {
            // New user - create with GOOGLE provider (works for both LOGIN and REGISTER modes)
            return createNewGoogleUser(email, googleId, givenName, familyName, picture, oauth2User);
        }
    }

    /**
     * Get OAuth2 mode from OAuth2 authorization request state parameter.
     * STATELESS: Extracts mode from state (no session usage).
     * Defaults to LOGIN if not set.
     */
    private OAuth2Mode getOAuth2ModeFromRequest(OAuth2UserRequest userRequest) {
        try {
            // The state parameter contains embedded mode: "originalState|mode=REGISTER"
            // However, OAuth2UserRequest doesn't directly expose the state.
            // The mode was embedded by CustomAuthorizationRequestResolver.
            // 
            // WORKAROUND: Check the authorization exchange for state
            // Note: Spring Security's OAuth2 flow validates state before reaching here,
            // so if we got here, the state was valid.
            var accessTokenResponse = userRequest.getAccessToken();
            var additionalParams = userRequest.getAdditionalParameters();
            
            // Check if mode was passed through additional parameters
            Object modeObj = additionalParams.get("mode");
            if (modeObj != null && "REGISTER".equals(modeObj.toString())) {
                return OAuth2Mode.REGISTER;
            }
            
            // Alternative: Check scopes or other indicators
            // For now, default to LOGIN - the CustomAuthorizationRequestResolver
            // stores mode in state, but we need the success handler to propagate it
            
        } catch (Exception ex) {
            log.warn("Could not extract OAuth2 mode from request: {}", ex.getMessage());
        }

        // Default to LOGIN mode
        return OAuth2Mode.LOGIN;
    }

    /**
     * Update existing Google user's profile information if changed
     */
    private OAuth2User updateExistingGoogleUser(User user, String givenName, String familyName,
                                                String picture, OAuth2User sourceUser) {
        boolean updated = false;

        if (givenName != null && !givenName.equals(user.getFirstName())) {
            user.setFirstName(givenName);
            updated = true;
        }

        if (familyName != null && !familyName.equals(user.getLastName())) {
            user.setLastName(familyName);
            updated = true;
        }

        if (picture != null && !picture.equals(user.getAvatarUrl())) {
            user.setAvatarUrl(picture);
            updated = true;
        }

        if (updated) {
            userRepository.save(user);
            log.info("Updated Google user profile: email={}", user.getEmail());
        } else {
            log.debug("Google user profile unchanged: email={}", user.getEmail());
        }

        return buildPrincipal(user, sourceUser);
    }

    /**
     * Create new user from Google OAuth2 information
     */
    private OAuth2User createNewGoogleUser(String email, String googleId,
                                           String givenName, String familyName, String picture,
                                           OAuth2User sourceUser) {
        User newUser = new User();
        newUser.setEmail(email);
        newUser.setGoogleId(googleId);
        newUser.setFirstName(givenName != null ? givenName : "User");
        
        // Handle missing lastName from Google - use placeholder
        if (familyName == null || familyName.isBlank()) {
            newUser.setLastName(User.GOOGLE_PLACEHOLDER_LAST_NAME);
        } else {
            newUser.setLastName(familyName);
        }
        
        newUser.setAvatarUrl(picture);
        newUser.setAuthProvider(AuthProvider.GOOGLE);
        newUser.setRole(Role.USER);
        newUser.setEnabled(true);
        newUser.setLocked(false);
        
        // Mark as INCOMPLETE - user must complete registration via /oauth-complete
        newUser.setRegistrationStatus(RegistrationStatus.INCOMPLETE);

        // Set placeholder password (column is non-null but won't be used for OAuth2 users)
        String placeholderPassword = OAUTH2_PLACEHOLDER_PASSWORD + UUID.randomUUID();
        newUser.setPassword(passwordEncoder.encode(placeholderPassword));

        User savedUser = userRepository.save(newUser);
        log.info("Created new Google user (INCOMPLETE): email={}, googleId={}", email, googleId);

        return buildPrincipal(savedUser, sourceUser);
    }

    private OAuth2User delegateToProperService(OAuth2UserRequest userRequest) {
        if (userRequest instanceof OidcUserRequest oidcRequest) {
            log.debug("Delegating to OIDC user service for provider: {}",
                    userRequest.getClientRegistration().getRegistrationId());
            return oidcUserService.loadUser(oidcRequest);
        }

        if (isOidcScope(userRequest)) {
            log.debug("OpenID scope detected without OidcUserRequest for provider: {}. Falling back to default loader.",
                    userRequest.getClientRegistration().getRegistrationId());
        }

        return super.loadUser(userRequest);
    }

    private boolean isOidcScope(OAuth2UserRequest userRequest) {
        return userRequest.getClientRegistration().getScopes().stream()
                .anyMatch(scope -> scope.equalsIgnoreCase("openid"));
    }

    private OAuth2UserPrincipal buildPrincipal(User user, OAuth2User sourceUser) {
        return new OAuth2UserPrincipal(user, sourceUser != null ? sourceUser.getAttributes() : null);
    }
}
