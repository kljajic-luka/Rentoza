package org.example.rentoza.auth.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import org.example.rentoza.user.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller providing Google OAuth2 helper endpoints.
 * These are read-only endpoints to help the frontend integrate with OAuth2.
 */
@RestController
@RequestMapping("/api/auth/google")
public class GoogleAuthController {

    /**
     * Get the Google OAuth2 authorization URL.
     * Frontend can redirect users to this URL to initiate the OAuth2 flow.
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

        return ResponseEntity.ok(response);
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
