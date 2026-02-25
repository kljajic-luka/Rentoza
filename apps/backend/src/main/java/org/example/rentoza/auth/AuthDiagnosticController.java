package org.example.rentoza.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.example.rentoza.config.AppProperties;
import org.example.rentoza.security.CookieConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Diagnostic endpoint for debugging authentication and cookie issues.
 *
 * <p>SECURITY: Restricted to non-production profiles via @Profile("!prod").
 * Defense-in-depth: @PreAuthorize requires ADMIN role even in dev.
 *
 * <p>Usage: GET /api/auth/debug/cookie-status
 * Returns information about current request context and cookie state.
 */
@RestController
@RequestMapping("/api/auth/debug")
@Profile("!prod")
@PreAuthorize("hasRole('ADMIN')")
public class AuthDiagnosticController {

    private final AppProperties appProperties;
    
    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    public AuthDiagnosticController(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * Debug endpoint to diagnose cookie transmission issues.
     * 
     * <p>Returns:
     * - Request details (scheme, host, origin)
     * - Cookie configuration
     * - Cookies received in request
     * - Potential issues detected
     */
    @GetMapping("/cookie-status")
    public ResponseEntity<Map<String, Object>> getCookieStatus(HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        
        // Request details
        Map<String, Object> requestInfo = new HashMap<>();
        requestInfo.put("scheme", request.getScheme());
        requestInfo.put("serverName", request.getServerName());
        requestInfo.put("serverPort", request.getServerPort());
        requestInfo.put("remoteAddr", request.getRemoteAddr());
        requestInfo.put("origin", request.getHeader("Origin"));
        requestInfo.put("referer", request.getHeader("Referer"));
        requestInfo.put("host", request.getHeader("Host"));
        requestInfo.put("isSecure", request.isSecure());
        response.put("request", requestInfo);
        
        // Cookie configuration
        Map<String, Object> cookieConfig = new HashMap<>();
        cookieConfig.put("secure", appProperties.getCookie().isSecure());
        cookieConfig.put("domain", appProperties.getCookie().getDomain());
        cookieConfig.put("sameSite", appProperties.getCookie().getSameSite());
        cookieConfig.put("activeProfile", activeProfile);
        response.put("cookieConfig", cookieConfig);
        
        // Cookies received
        Map<String, Object> cookiesReceived = new HashMap<>();
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            cookiesReceived.put("count", cookies.length);
            Map<String, String> cookieNames = new HashMap<>();
            for (Cookie cookie : cookies) {
                // Don't expose full token values
                String value = cookie.getName().contains("token") 
                        ? "[REDACTED-" + cookie.getValue().length() + "-chars]"
                        : cookie.getValue();
                cookieNames.put(cookie.getName(), value);
            }
            cookiesReceived.put("cookies", cookieNames);
            cookiesReceived.put("hasAccessToken", 
                    java.util.Arrays.stream(cookies)
                            .anyMatch(c -> CookieConstants.ACCESS_TOKEN.equals(c.getName())));
            cookiesReceived.put("hasRefreshToken", 
                    java.util.Arrays.stream(cookies)
                            .anyMatch(c -> CookieConstants.REFRESH_TOKEN.equals(c.getName())));
        } else {
            cookiesReceived.put("count", 0);
            cookiesReceived.put("hasAccessToken", false);
            cookiesReceived.put("hasRefreshToken", false);
        }
        response.put("cookiesReceived", cookiesReceived);
        
        // Detect potential issues
        java.util.List<String> issues = new java.util.ArrayList<>();
        
        // Issue 1: Secure cookie over HTTP
        if (appProperties.getCookie().isSecure() && !request.isSecure()) {
            issues.add("SECURE_OVER_HTTP: Cookie has Secure flag but request is HTTP. " +
                    "Set COOKIE_SECURE=false for HTTP development.");
        }
        
        // Issue 2: Domain mismatch with IP address
        String host = request.getServerName();
        String configDomain = appProperties.getCookie().getDomain();
        if (host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) { // IP address
            if (configDomain != null && !configDomain.isBlank() && !configDomain.equals(host)) {
                issues.add("DOMAIN_MISMATCH_IP: Accessing via IP (" + host + ") but cookie domain is '" + 
                        configDomain + "'. Leave COOKIE_DOMAIN empty for IP access.");
            }
        } else if (configDomain != null && !configDomain.isBlank() && 
                   !host.equals(configDomain) && !host.endsWith("." + configDomain)) {
            issues.add("DOMAIN_MISMATCH: Request host (" + host + ") doesn't match cookie domain (" + 
                    configDomain + ")");
        }
        
        // Issue 3: Missing cookies when expected
        if (cookies == null || cookies.length == 0) {
            issues.add("NO_COOKIES: No cookies received. Check browser devtools > Application > Cookies. " +
                    "Cookies may be blocked due to domain/secure/sameSite mismatch.");
        }
        
        // Issue 4: SameSite=Strict with cross-origin
        String origin = request.getHeader("Origin");
        if ("Strict".equalsIgnoreCase(appProperties.getCookie().getSameSite())) {
            if (origin != null && !origin.contains(host)) {
                issues.add("SAMESITE_STRICT: SameSite=Strict may block cookies on cross-origin requests. " +
                        "Use SameSite=Lax for development.");
            }
        }
        
        response.put("potentialIssues", issues);
        response.put("recommendation", issues.isEmpty() 
                ? "Configuration looks correct for this request context."
                : "Fix the issues above. For development with IP address access, use: " +
                  "COOKIE_SECURE=false, COOKIE_DOMAIN= (empty), COOKIE_SAME_SITE=Lax");
        
        return ResponseEntity.ok(response);
    }
}
