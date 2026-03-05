/**
 * Centralized constants for cookie and token names used across the frontend application.
 *
 * Security Design:
 * - Single source of truth for all cookie identifiers
 * - Prevents typos and inconsistencies across authentication flows
 * - Enables IDE refactoring and compile-time verification
 *
 * Usage:
 * - token.interceptor.ts: XSRF token extraction and header injection
 * - auth.service.ts: Session management and cookie-based authentication
 * - websocket.service.ts: Authentication header construction
 */

// ============ AUTHENTICATION COOKIES ============

/**
 * HttpOnly cookie containing the JWT access token.
 * Note: Frontend cannot read this cookie (HttpOnly=true)
 * Backend sets this cookie on login/register/refresh responses.
 */
export const ACCESS_TOKEN_COOKIE = 'access_token';

/**
 * HttpOnly cookie containing the refresh token.
 * Note: Frontend cannot read this cookie (HttpOnly=true)
 * Backend sets this cookie with narrow path: /api/auth/supabase/refresh
 */
export const REFRESH_TOKEN_COOKIE = 'rentoza_refresh';

// ============ CSRF PROTECTION ============

/**
 * Non-HttpOnly cookie for CSRF token (Angular XSRF integration).
 * HttpOnly=false so JavaScript can read and submit it.
 */
export const XSRF_TOKEN_COOKIE = 'XSRF-TOKEN';

/**
 * Header name for CSRF token submission.
 * Angular's HttpClient reads XSRF-TOKEN cookie and submits it in this header.
 */
export const XSRF_TOKEN_HEADER = 'X-XSRF-TOKEN';

// ============ LOCALSTORAGE KEYS (CLEANUP FALLBACK) ============

/**
 * localStorage key for access token.
 * @deprecated Retained only to clear data from pre-cookie clients on upgrade.
 */
export const LOCALSTORAGE_ACCESS_TOKEN = 'access_token';

/**
 * localStorage key for current user profile.
 * @deprecated Retained only to clear data from pre-cookie clients on upgrade.
 */
export const LOCALSTORAGE_CURRENT_USER = 'current_user';

// ============ AUTH ENDPOINT SEGMENTS ============

/**
 * URL segments that should bypass authentication interceptor.
 * These endpoints handle their own authentication flow.
 */
export const AUTH_BYPASS_SEGMENTS = [
  '/auth/login',
  '/auth/register',
  '/auth/supabase/refresh',
  '/auth/logout',
] as const;

// ============ AUTHORIZATION ============

/**
 * HTTP Authorization header name.
 */
export const AUTHORIZATION_HEADER = 'Authorization';

/**
 * Bearer token prefix in Authorization header.
 */
export const BEARER_PREFIX = 'Bearer ';

// ============ CONVENIENCE OBJECTS ============

/**
 * Cookie name constants grouped for easy import.
 */
export const COOKIE_NAMES = {
  ACCESS_TOKEN: ACCESS_TOKEN_COOKIE,
  REFRESH_TOKEN: REFRESH_TOKEN_COOKIE,
  XSRF_TOKEN: XSRF_TOKEN_COOKIE,
} as const;

/**
 * Header name constants grouped for easy import.
 */
export const HEADER_NAMES = {
  XSRF: XSRF_TOKEN_HEADER,
  AUTHORIZATION: AUTHORIZATION_HEADER,
} as const;

/**
 * Auth endpoint segments for interceptor bypass.
 */
export const AUTH_ENDPOINTS: readonly string[] = AUTH_BYPASS_SEGMENTS;
