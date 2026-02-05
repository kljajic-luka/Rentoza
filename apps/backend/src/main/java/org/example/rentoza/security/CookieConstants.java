package org.example.rentoza.security;

/**
 * Centralized constants for cookie and token names used across the application.
 * 
 * Security Design:
 * - Single source of truth for all cookie identifiers
 * - Prevents typos and inconsistencies across authentication flows
 * - Enables IDE refactoring and compile-time verification
 * 
 * Usage:
 * - AuthController: Cookie creation and extraction
 * - JwtAuthFilter: Token extraction from cookies
 * - WebSocketAuthInterceptor: WebSocket handshake authentication
 * - CSRF protection: Token repository configuration
 */
public final class CookieConstants {
    
    private CookieConstants() {
        // Prevent instantiation - utility class
    }
    
    // ============ AUTHENTICATION COOKIES ============
    
    /**
     * HttpOnly cookie containing the JWT access token.
     * - Path: /
     * - HttpOnly: true (prevents XSS access)
     * - Secure: environment-dependent
     * - SameSite: Lax (CSRF protection)
     */
    public static final String ACCESS_TOKEN = "access_token";
    
    /**
     * HttpOnly cookie containing the refresh token.
     * - Path: /api/auth/refresh (narrow scope for security)
     * - HttpOnly: true
     * - Secure: environment-dependent
     * - SameSite: Lax
     * - MaxAge: 14 days
     */
    public static final String REFRESH_TOKEN = "rentoza_refresh";
    
    // ============ CSRF PROTECTION ============
    
    /**
     * Non-HttpOnly cookie for CSRF token (Angular XSRF integration).
     * - Path: /
     * - HttpOnly: false (must be readable by JavaScript)
     * - Secure: environment-dependent
     * - SameSite: Lax
     */
    public static final String XSRF_TOKEN = "XSRF-TOKEN";
    
    /**
     * Header name for CSRF token submission.
     * Angular's HttpClient automatically reads XSRF-TOKEN cookie
     * and submits it in this header.
     */
    public static final String XSRF_HEADER = "X-XSRF-TOKEN";
    
    /**
     * Form parameter name for CSRF token (fallback).
     */
    public static final String XSRF_PARAMETER = "_csrf";
    
    // ============ AUTHORIZATION ============
    
    /**
     * HTTP Authorization header name.
     */
    public static final String AUTHORIZATION_HEADER = "Authorization";
    
    /**
     * Bearer token prefix in Authorization header.
     */
    public static final String BEARER_PREFIX = "Bearer ";
}
