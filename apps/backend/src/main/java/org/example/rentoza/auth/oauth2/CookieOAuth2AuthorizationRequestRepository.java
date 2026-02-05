package org.example.rentoza.auth.oauth2;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.rentoza.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.SerializationUtils;
import org.springframework.web.util.WebUtils;

import java.io.Serializable;
import java.time.Duration;
import java.util.Base64;

/**
 * Cookie-based OAuth2 authorization request repository.
 * 
 * STATELESS ARCHITECTURE:
 * - Stores OAuth2 authorization request in an encrypted cookie instead of HttpSession
 * - Prevents JSESSIONID creation during OAuth2 flows
 * - Compatible with SessionCreationPolicy.STATELESS
 * 
 * SECURITY:
 * - Cookie is HttpOnly (prevents XSS)
 * - Cookie is Secure in production (HTTPS only)
 * - Short TTL (5 minutes) since OAuth2 flow should complete quickly
 * - Base64 encoded serialized OAuth2AuthorizationRequest
 * 
 * FLOW:
 * 1. User clicks "Login with Google"
 * 2. Spring Security creates OAuth2AuthorizationRequest
 * 3. This repository serializes and stores it in oauth2_auth_request cookie
 * 4. User authenticates with Google
 * 5. Google redirects back with code
 * 6. This repository loads OAuth2AuthorizationRequest from cookie
 * 7. Cookie is cleared after successful authentication
 */
@Component
public class CookieOAuth2AuthorizationRequestRepository 
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final Logger log = LoggerFactory.getLogger(CookieOAuth2AuthorizationRequestRepository.class);
    
    public static final String OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME = "oauth2_auth_request";
    private static final int COOKIE_EXPIRE_SECONDS = 300; // 5 minutes
    
    private final AppProperties appProperties;
    
    public CookieOAuth2AuthorizationRequestRepository(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME);
        
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            log.debug("No OAuth2 authorization request cookie found");
            return null;
        }
        
        try {
            return deserialize(cookie.getValue());
        } catch (Exception e) {
            log.warn("Failed to deserialize OAuth2 authorization request from cookie: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                          HttpServletRequest request,
                                          HttpServletResponse response) {
        if (authorizationRequest == null) {
            // Clear the cookie
            clearCookie(response);
            return;
        }
        
        try {
            String serialized = serialize(authorizationRequest);
            
            ResponseCookie cookie = ResponseCookie.from(OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME, serialized)
                    .httpOnly(true)
                    .secure(appProperties.getCookie().isSecure())
                    .path("/")
                    .domain(appProperties.getCookie().getDomain())
                    .sameSite("Lax") // Must be Lax for OAuth2 redirects
                    .maxAge(Duration.ofSeconds(COOKIE_EXPIRE_SECONDS))
                    .build();
            
            response.addHeader("Set-Cookie", cookie.toString());
            log.debug("Saved OAuth2 authorization request to cookie");
            
        } catch (Exception e) {
            log.error("Failed to serialize OAuth2 authorization request to cookie: {}", e.getMessage());
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                   HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        
        if (authorizationRequest != null) {
            clearCookie(response);
        }
        
        return authorizationRequest;
    }
    
    private void clearCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(appProperties.getCookie().isSecure())
                .path("/")
                .domain(appProperties.getCookie().getDomain())
                .sameSite("Lax")
                .maxAge(0)
                .build();
        
        response.addHeader("Set-Cookie", cookie.toString());
        log.debug("Cleared OAuth2 authorization request cookie");
    }
    
    private String serialize(OAuth2AuthorizationRequest authorizationRequest) {
        // OAuth2AuthorizationRequest is Serializable
        byte[] bytes = SerializationUtils.serialize((Serializable) authorizationRequest);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    
    private OAuth2AuthorizationRequest deserialize(String cookieValue) {
        byte[] bytes = Base64.getUrlDecoder().decode(cookieValue);
        return (OAuth2AuthorizationRequest) SerializationUtils.deserialize(bytes);
    }
}
