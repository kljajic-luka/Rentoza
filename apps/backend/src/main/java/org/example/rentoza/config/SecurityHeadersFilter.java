package org.example.rentoza.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Security headers filter for production hardening
 * Adds CSP, HSTS, and other security headers to all responses
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip CORS preflight requests - let CorsFilter handle them
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Content Security Policy - restrict resource sources
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; " +
                "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://fonts.googleapis.com; " +
                "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
                "font-src 'self' https://fonts.gstatic.com data:; " +
                "img-src 'self' data: https: blob:; " +
                "connect-src 'self'; " +
                "frame-ancestors 'none'; " +
                "base-uri 'self'; " +
                "form-action 'self'");

        // Strict Transport Security - enforce HTTPS in production
        if ("prod".equals(activeProfile)) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload");
        }

        // X-Frame-Options - prevent clickjacking
        response.setHeader("X-Frame-Options", "DENY");

        // X-Content-Type-Options - prevent MIME sniffing
        response.setHeader("X-Content-Type-Options", "nosniff");

        // X-XSS-Protection - enable XSS filter
        response.setHeader("X-XSS-Protection", "1; mode=block");

        // Referrer-Policy - control referrer information
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // Permissions-Policy - restrict browser features
        response.setHeader("Permissions-Policy",
                "geolocation=(), microphone=(), camera=(), payment=()");

        // Vary header - proper caching with compression
        if (!response.containsHeader("Vary")) {
            response.setHeader("Vary", "Accept-Encoding, Origin");
        }

        filterChain.doFilter(request, response);
    }
}
