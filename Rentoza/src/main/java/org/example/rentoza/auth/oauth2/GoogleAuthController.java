package org.example.rentoza.auth.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.example.rentoza.user.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Controller providing Google OAuth2 helper endpoints.
 * These are read-only endpoints to help the frontend integrate with OAuth2.
 */
@RestController
@RequestMapping("/api/auth/google")
public class GoogleAuthController {

    public static final String OAUTH2_MODE_SESSION_KEY = "oauth2_mode";

    /**
     * Get the Google OAuth2 authorization URL for login.
     * Frontend can redirect users to this URL to initiate the OAuth2 login flow.
     *
     * @return JSON with the Google login URL
     */
    @GetMapping("/url")
    public ResponseEntity<Map<String, String>> getGoogleAuthUrl(HttpServletRequest request) {
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(null)
                .build()
                .toUriString();

        String googleAuthUrl = baseUrl + "/oauth2/authorization/google";

        Map<String, String> response = new HashMap<>();
        response.put("url", googleAuthUrl);
        response.put("provider", "google");
        response.put("mode", "login");

        return ResponseEntity.ok(response);
    }

    /**
     * Get the Google OAuth2 authorization URL for registration.
     * Frontend can redirect users to this URL to initiate the OAuth2 registration flow.
     *
     * @return JSON with the Google registration URL
     */
    @GetMapping("/register-url")
    public ResponseEntity<Map<String, String>> getGoogleRegisterUrl(HttpServletRequest request) {
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(null)
                .build()
                .toUriString();

        String googleRegisterUrl = baseUrl + "/api/auth/google/register";

        Map<String, String> response = new HashMap<>();
        response.put("url", googleRegisterUrl);
        response.put("provider", "google");
        response.put("mode", "register");

        return ResponseEntity.ok(response);
    }

    /**
     * Initiate Google OAuth2 registration flow.
     * Sets REGISTER mode in session and redirects to OAuth2 authorization endpoint.
     *
     * This endpoint is called by the frontend when user clicks "Register with Google".
     * It stores the registration intent in the session, then redirects to the standard
     * OAuth2 authorization flow.
     *
     * @param request HTTP request
     * @param response HTTP response for redirect
     * @throws IOException if redirect fails
     */
    @GetMapping("/register")
    public void initiateGoogleRegistration(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // Store registration mode in session
        HttpSession session = request.getSession(true);
        session.setAttribute(OAUTH2_MODE_SESSION_KEY, OAuth2Mode.REGISTER);

        // Redirect to standard OAuth2 authorization endpoint
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(null)
                .build()
                .toUriString();

        String oauth2AuthUrl = baseUrl + "/oauth2/authorization/google";
        response.sendRedirect(oauth2AuthUrl);
    }

    /**
     * Test endpoint to verify OAuth2 authentication is working.
     * Returns user information if authenticated via OAuth2.
     *
     * @param principal the authenticated OAuth2 user principal
     * @return JSON with user information
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getOAuth2User(
            @AuthenticationPrincipal OAuth2UserPrincipal principal) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "Not authenticated",
                    "message", "This endpoint requires OAuth2 authentication"
            ));
        }

        User user = principal.getUser();
        Map<String, Object> response = new HashMap<>();
        response.put("id", user.getId());
        response.put("email", user.getEmail());
        response.put("firstName", user.getFirstName());
        response.put("lastName", user.getLastName());
        response.put("provider", user.getAuthProvider().name());
        response.put("googleId", user.getGoogleId());
        response.put("role", user.getRole().name());

        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint for OAuth2 configuration.
     * Returns configuration status without sensitive information.
     *
     * @return JSON with OAuth2 configuration status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getOAuth2Status() {
        Map<String, Object> response = new HashMap<>();
        response.put("oauth2Enabled", true);
        response.put("provider", "google");
        response.put("authorizationUrl", "/oauth2/authorization/google");
        response.put("redirectUri", "/login/oauth2/code/google");

        return ResponseEntity.ok(response);
    }
}
