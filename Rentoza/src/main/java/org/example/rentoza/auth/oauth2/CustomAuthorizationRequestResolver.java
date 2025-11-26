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
 * Custom OAuth2 authorization request resolver that captures role and mode context from the request
 * and embeds it securely in the OAuth2 state parameter.
 *
 * SECURITY DESIGN:
 * - The OAuth2 state parameter is designed to prevent CSRF attacks
 * - We extend it to carry additional context (role, mode) through the OAuth2 flow
 * - Format: "base64(originalState|role=ROLE_VALUE|mode=MODE_VALUE)"
 * - The state is validated by Spring Security's OAuth2 flow before success handler runs
 * - Server-side validation ensures role can only be USER or OWNER (not arbitrary values)
 *
 * STATELESS ARCHITECTURE:
 * - No session storage required (prevents JSESSIONID creation)
 * - Role and mode context flows through OAuth2 redirect chain
 * - Compatible with SessionCreationPolicy.STATELESS
 * - Replaces previous session-based mode passing
 */
@Component
public class CustomAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final Logger log = LoggerFactory.getLogger(CustomAuthorizationRequestResolver.class);
    private static final String ROLE_PARAM = "role";
    private static final String MODE_PARAM = "mode";
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
     * Customizes the OAuth2 authorization request to include role and mode context in the state parameter.
     *
     * Flow:
     * 1. Frontend calls: /oauth2/authorization/google?role=owner&mode=register
     * 2. This resolver extracts ?role=owner and ?mode=register from the request
     * 3. Embeds both into OAuth2 state: "originalState|role=owner|mode=register"
     * 4. Google redirects back with this state
     * 5. OAuth2AuthenticationSuccessHandler/CustomOAuth2UserService extracts context from state
     * 6. User is provisioned with appropriate role and mode handling
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
        
        // Extract mode parameter from query string (login vs register)
        String modeParam = request.getParameter(MODE_PARAM);

        // FALLBACK: If no role parameter, check Referer header for owner registration intent
        if (roleParam == null || roleParam.isBlank()) {
            String referer = request.getHeader("Referer");
            
            if (referer != null && referer.contains("/auth/register") && referer.contains("role=owner")) {
                roleParam = "owner";
            }
        }
        
        // Validate and normalize parameters
        String validatedRole = validateRole(roleParam);
        String validatedMode = validateMode(modeParam);
        
        // If no customization needed, return original request
        if (validatedRole == null && validatedMode == null) {
            return authorizationRequest;
        }

        // Get original state
        String originalState = authorizationRequest.getState();

        if (originalState == null) {
            log.warn("OAuth2 authorization request missing state parameter. Cannot embed context.");
            return authorizationRequest;
        }

        // Embed role and mode in state parameter: "originalState|role=OWNER|mode=REGISTER"
        String customState = embedContextInState(originalState, validatedRole, validatedMode);

        // Build new authorization request with custom state
        Map<String, Object> additionalParameters = new HashMap<>(authorizationRequest.getAdditionalParameters());

        OAuth2AuthorizationRequest customRequest = OAuth2AuthorizationRequest
                .from(authorizationRequest)
                .state(customState)
                .additionalParameters(additionalParameters)
                .build();

        log.debug("OAuth2 authorization request customized with role: {}, mode: {}", validatedRole, validatedMode);

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
     * Validates the mode parameter to ensure only valid modes are accepted.
     * Valid modes: "register" or "login" (default if not specified)
     *
     * SECURITY: Prevents arbitrary mode injection
     */
    private String validateMode(String modeParam) {
        if (modeParam == null || modeParam.isBlank()) {
            return null;
        }

        String normalized = modeParam.trim().toLowerCase();

        // Only allow "register" mode to be explicitly requested
        if ("register".equals(normalized)) {
            return "REGISTER";
        }

        // "login" is default, no need to embed
        return null;
    }

    /**
     * Embeds role and mode context into OAuth2 state parameter.
     * Format: "originalState|role=OWNER|mode=REGISTER"
     *
     * The state parameter is returned by OAuth2 provider and validated by Spring Security,
     * so this is a secure way to carry context through the flow.
     */
    private String embedContextInState(String originalState, String role, String mode) {
        StringBuilder sb = new StringBuilder(originalState);
        
        if (role != null) {
            sb.append(STATE_DELIMITER).append(ROLE_PARAM).append("=").append(role);
        }
        
        if (mode != null) {
            sb.append(STATE_DELIMITER).append(MODE_PARAM).append("=").append(mode);
        }
        
        return sb.toString();
    }

    /**
     * Extracts role from OAuth2 state parameter (used by success handler).
     * Returns null if no role context found or if state format is invalid.
     */
    public static String extractRoleFromState(String state) {
        return extractParamFromState(state, ROLE_PARAM);
    }
    
    /**
     * Extracts mode from OAuth2 state parameter (used by CustomOAuth2UserService).
     * Returns null if no mode context found (defaults to LOGIN).
     */
    public static String extractModeFromState(String state) {
        return extractParamFromState(state, MODE_PARAM);
    }
    
    /**
     * Generic extraction of parameter from OAuth2 state.
     */
    private static String extractParamFromState(String state, String paramName) {
        if (state == null || !state.contains(STATE_DELIMITER)) {
            return null;
        }

        try {
            String[] parts = state.split("\\" + STATE_DELIMITER);

            for (int i = 1; i < parts.length; i++) {
                String part = parts[i];
                String prefix = paramName + "=";
                
                if (part.startsWith(prefix)) {
                    return part.substring(prefix.length());
                }
            }
        } catch (Exception e) {
            Logger log = LoggerFactory.getLogger(CustomAuthorizationRequestResolver.class);
            log.warn("Error extracting {} from OAuth2 state: {}", paramName, e.getMessage());
        }

        return null;
    }
}
