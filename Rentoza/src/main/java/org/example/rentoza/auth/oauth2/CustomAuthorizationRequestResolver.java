package org.example.rentoza.auth.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom OAuth2 authorization request resolver that captures role context from the request
 * and embeds it securely in the OAuth2 state parameter.
 *
 * SECURITY DESIGN:
 * - The OAuth2 state parameter is designed to prevent CSRF attacks
 * - We extend it to carry additional context (role) through the OAuth2 flow
 * - Format: "base64(originalState|role=ROLE_VALUE)"
 * - The state is validated by Spring Security's OAuth2 flow before success handler runs
 * - Server-side validation ensures role can only be USER or OWNER (not arbitrary values)
 *
 * STATELESS ARCHITECTURE:
 * - No session storage required
 * - Role context flows through OAuth2 redirect chain
 * - Compatible with SessionCreationPolicy.STATELESS
 */
@Component
public class CustomAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final Logger log = LoggerFactory.getLogger(CustomAuthorizationRequestResolver.class);
    private static final String ROLE_PARAM = "role";
    private static final String STATE_DELIMITER = "|";

    private final DefaultOAuth2AuthorizationRequestResolver defaultResolver;

    public CustomAuthorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository) {
        this.defaultResolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                "/oauth2/authorization"
        );
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        OAuth2AuthorizationRequest authorizationRequest = defaultResolver.resolve(request);
        return customizeAuthorizationRequest(authorizationRequest, request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest authorizationRequest = defaultResolver.resolve(request, clientRegistrationId);
        return customizeAuthorizationRequest(authorizationRequest, request);
    }

    /**
     * Customizes the OAuth2 authorization request to include role context in the state parameter.
     *
     * Flow:
     * 1. Frontend calls: /oauth2/authorization/google?role=owner
     * 2. This resolver extracts ?role=owner from the request
     * 3. Embeds role into OAuth2 state: "originalState|role=owner"
     * 4. Google redirects back with this state
     * 5. OAuth2AuthenticationSuccessHandler extracts role from state
     * 6. User is provisioned with OWNER role
     */
    private OAuth2AuthorizationRequest customizeAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request) {

        if (authorizationRequest == null) {
            log.debug("OAuth2 authorization request is null, skipping customization");
            return null;
        }

        // Extract role parameter from query string
        String roleParam = request.getParameter(ROLE_PARAM);

        // FALLBACK: If no role parameter, check Referer header for owner registration intent
        if (roleParam == null || roleParam.isBlank()) {
            String referer = request.getHeader("Referer");
            
            if (referer != null && referer.contains("/auth/register") && referer.contains("role=owner")) {
                roleParam = "owner";
            } else {
                return authorizationRequest;
            }
        }

        // SECURITY: Validate role parameter - only allow specific values
        String validatedRole = validateRole(roleParam);

        if (validatedRole == null) {
            log.warn("Invalid role parameter in OAuth2 request: {}. Ignoring and defaulting to USER.", roleParam);
            return authorizationRequest;
        }

        // Get original state
        String originalState = authorizationRequest.getState();

        if (originalState == null) {
            log.warn("OAuth2 authorization request missing state parameter. Cannot embed role context.");
            return authorizationRequest;
        }

        // Embed role in state parameter: "originalState|role=OWNER"
        String customState = embedRoleInState(originalState, validatedRole);

        // Build new authorization request with custom state
        Map<String, Object> additionalParameters = new HashMap<>(authorizationRequest.getAdditionalParameters());

        OAuth2AuthorizationRequest customRequest = OAuth2AuthorizationRequest
                .from(authorizationRequest)
                .state(customState)
                .additionalParameters(additionalParameters)
                .build();

        log.debug("OAuth2 authorization request customized with role: {}", validatedRole);

        return customRequest;
    }

    /**
     * Validates the role parameter to prevent arbitrary role assignment.
     * Only allows "owner" to be elevated. All other values default to USER.
     *
     * SECURITY: This is critical - prevents client from requesting admin/system roles
     */
    private String validateRole(String roleParam) {
        if (roleParam == null || roleParam.isBlank()) {
            return null;
        }

        String normalized = roleParam.trim().toLowerCase();

        // Only allow "owner" role to be explicitly requested
        if ("owner".equals(normalized)) {
            return "OWNER";
        }

        // Ignore any other role values (including admin, system, etc.)
        return null;
    }

    /**
     * Embeds role context into OAuth2 state parameter.
     * Format: "originalState|role=OWNER"
     *
     * The state parameter is returned by OAuth2 provider and validated by Spring Security,
     * so this is a secure way to carry context through the flow.
     */
    private String embedRoleInState(String originalState, String role) {
        return originalState + STATE_DELIMITER + ROLE_PARAM + "=" + role;
    }

    /**
     * Extracts role from OAuth2 state parameter (used by success handler).
     * Returns null if no role context found or if state format is invalid.
     */
    public static String extractRoleFromState(String state) {
        if (state == null || !state.contains(STATE_DELIMITER)) {
            return null;
        }

        try {
            String[] parts = state.split("\\" + STATE_DELIMITER, 2);

            if (parts.length < 2) {
                return null;
            }

            String contextPart = parts[1];
            String rolePrefix = ROLE_PARAM + "=";

            if (contextPart.startsWith(rolePrefix)) {
                return contextPart.substring(rolePrefix.length());
            }
        } catch (Exception e) {
            Logger log = LoggerFactory.getLogger(CustomAuthorizationRequestResolver.class);
            log.warn("Error extracting role from OAuth2 state: {}", e.getMessage());
        }

        return null;
    }
}
