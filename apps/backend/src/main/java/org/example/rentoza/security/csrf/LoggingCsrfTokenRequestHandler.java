package org.example.rentoza.security.csrf;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.util.WebUtils;

/**
 * Debug-friendly {@link org.springframework.security.web.csrf.CsrfTokenRequestHandler}
 * that logs the header/parameter/cookie tokens made available to Spring Security.
 *
 * Enabled only via DEBUG logging and skips noisy GET/HEAD requests to focus on
 * state-changing calls. Helps diagnose mismatches where the request header does
 * not match the repository-issued cookie.
 */
public class LoggingCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingCsrfTokenRequestHandler.class);

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       Supplier<CsrfToken> csrfTokenSupplier) {
        super.handle(request, response, csrfTokenSupplier);

        if (!log.isDebugEnabled()) {
            return;
        }

        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            return; // skip read-only noise
        }

        CsrfToken token = csrfTokenSupplier.get();
        if (token == null) {
            log.debug("[CSRF] No repository token present for {}", request.getRequestURI());
            return;
        }

        String headerValue = request.getHeader(token.getHeaderName());
        String paramValue = request.getParameter(token.getParameterName());
        Cookie cookie = WebUtils.getCookie(request, CustomCookieCsrfTokenRepository.DEFAULT_CSRF_COOKIE_NAME);
        String cookieValue = cookie != null ? cookie.getValue() : null;

        log.debug(
                "[CSRF] Request={} header='{}' param='{}' cookie='{}' repo='{}'",
                request.getRequestURI(),
                abbreviate(headerValue),
                abbreviate(paramValue),
                abbreviate(cookieValue),
                abbreviate(token.getToken())
        );
    }

    private String abbreviate(String value) {
        if (!StringUtils.hasText(value)) {
            return "<empty>";
        }
        if (value.length() <= 12) {
            return value;
        }
        return value.substring(0, 8) + "…" + value.substring(value.length() - 4);
    }
}
