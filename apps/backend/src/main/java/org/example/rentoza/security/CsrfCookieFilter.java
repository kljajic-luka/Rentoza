package org.example.rentoza.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter to ensure the CSRF token is loaded and the cookie is written to the response.
 * Spring Security's CSRF token is lazily loaded by default, meaning the cookie
 * isn't written until the token is accessed. This filter forces access to the token.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CsrfCookieFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            // Render the token value to a cookie by causing the deferred token to be loaded
            csrfToken.getToken();
            if (log.isTraceEnabled()) {
                log.trace("CSRF token present for {}", request.getRequestURI());
            }
        } else if (log.isDebugEnabled()) {
            log.debug("No CSRF token attribute found for {}", request.getRequestURI());
        }
        filterChain.doFilter(request, response);
    }
}
