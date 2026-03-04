package org.example.rentoza.security.csrf;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.example.rentoza.config.AppProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.util.StringUtils;
import org.springframework.web.util.WebUtils;

/**
 * Custom CSRF token repository that issues cookies aligned with our AppProperties configuration.
 * 
 * Adds explicit Domain/SameSite/Secure flags so browsers consistently expose the XSRF-TOKEN
 * cookie to Angular while still allowing Spring Security to validate submitted tokens.
 */
public class CustomCookieCsrfTokenRepository implements CsrfTokenRepository {

    public static final String DEFAULT_CSRF_PARAMETER_NAME = "_csrf";
    public static final String DEFAULT_CSRF_HEADER_NAME = "X-XSRF-TOKEN";
    public static final String DEFAULT_CSRF_COOKIE_NAME = "XSRF-TOKEN";

    private final SecureRandom secureRandom = new SecureRandom();
    private final AppProperties appProperties;

    public CustomCookieCsrfTokenRepository(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String tokenValue = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new DefaultCsrfToken(DEFAULT_CSRF_HEADER_NAME, DEFAULT_CSRF_PARAMETER_NAME, tokenValue);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        Cookie existing = WebUtils.getCookie(request, DEFAULT_CSRF_COOKIE_NAME);

        if (token == null || !StringUtils.hasText(token.getToken())) {
            // Clear cookie if token removed
            ResponseCookie clearCookie = ResponseCookie.from(DEFAULT_CSRF_COOKIE_NAME, "")
                    .httpOnly(false)
                    .secure(appProperties.getCookie().isSecure())
                    .path("/")
                    .domain(appProperties.getCookie().getDomain())
                    .sameSite(appProperties.getCookie().getSameSite())
                    .maxAge(0)
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());
            return;
        }

        // Skip rewrite if cookie already carries the same token value
        if (existing != null && StringUtils.hasText(existing.getValue()) && existing.getValue().equals(token.getToken())) {
            return;
        }

        ResponseCookie cookie = ResponseCookie.from(DEFAULT_CSRF_COOKIE_NAME, token.getToken())
                .httpOnly(false)
                .secure(appProperties.getCookie().isSecure())
                .path("/")
                .domain(appProperties.getCookie().getDomain())
                .sameSite(appProperties.getCookie().getSameSite())
                // Align CSRF cookie lifetime with refresh token (14 days)
                // so the token survives browser restarts alongside the session.
                .maxAge(Duration.ofDays(14))
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, DEFAULT_CSRF_COOKIE_NAME);
        if (cookie == null) {
            return null;
        }

        String token = cookie.getValue();
        if (!StringUtils.hasText(token)) {
            return null;
        }

        return new DefaultCsrfToken(DEFAULT_CSRF_HEADER_NAME, DEFAULT_CSRF_PARAMETER_NAME, token);
    }
}
