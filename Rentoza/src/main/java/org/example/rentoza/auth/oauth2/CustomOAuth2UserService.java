package org.example.rentoza.auth.oauth2;

import org.example.rentoza.user.AuthProvider;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * Handles three scenarios:
 * 1. New user -> create with GOOGLE provider
 * 2. Existing GOOGLE user -> update profile if changed
 * 3. Existing LOCAL user -> throw exception (email already registered with password)
 */
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private static final Logger log = LoggerFactory.getLogger(CustomOAuth2UserService.class);
    private static final String OAUTH2_PLACEHOLDER_PASSWORD = "OAUTH2_NO_PASSWORD_";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public CustomOAuth2UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        // Delegate to default implementation to load user info from Google
        OAuth2User oauth2User = super.loadUser(userRequest);

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
     * Process OAuth2 user information from Google and create or update user record
     */
    private OAuth2User processOAuth2User(OAuth2UserRequest userRequest, OAuth2User oauth2User) {
        String registrationId = userRequest.getClientRegistration().getRegistrationId();

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
            log.warn("OAuth2 login attempt with unverified email: {}", email);
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
            // User exists - check provider
            if (user.getAuthProvider() == AuthProvider.LOCAL) {
                // Email already registered with local authentication
                log.warn("OAuth2 login attempt for email already registered locally: {}", email);
                throw new OAuth2AuthenticationException(
                    new OAuth2Error(
                        "email_already_registered",
                        "This email is already registered with password authentication. Please log in using your password.",
                        null
                    )
                );
            }

            // User exists with GOOGLE provider - update profile if changed
            return updateExistingGoogleUser(user, givenName, familyName, picture);
        } else {
            // New user - create with GOOGLE provider
            return createNewGoogleUser(email, googleId, givenName, familyName, picture);
        }
    }

    /**
     * Update existing Google user's profile information if changed
     */
    private OAuth2User updateExistingGoogleUser(User user, String givenName, String familyName, String picture) {
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

        return new OAuth2UserPrincipal(user, null);
    }

    /**
     * Create new user from Google OAuth2 information
     */
    private OAuth2User createNewGoogleUser(String email, String googleId,
                                           String givenName, String familyName, String picture) {
        User newUser = new User();
        newUser.setEmail(email);
        newUser.setGoogleId(googleId);
        newUser.setFirstName(givenName != null ? givenName : "User");
        newUser.setLastName(familyName != null ? familyName : "");
        newUser.setAvatarUrl(picture);
        newUser.setAuthProvider(AuthProvider.GOOGLE);
        newUser.setRole(Role.USER);
        newUser.setEnabled(true);
        newUser.setLocked(false);

        // Set placeholder password (column is non-null but won't be used for OAuth2 users)
        String placeholderPassword = OAUTH2_PLACEHOLDER_PASSWORD + UUID.randomUUID();
        newUser.setPassword(passwordEncoder.encode(placeholderPassword));

        User savedUser = userRepository.save(newUser);
        log.info("Created new Google user: email={}, googleId={}", email, googleId);

        return new OAuth2UserPrincipal(savedUser, null);
    }
}
