import { HttpInterceptorFn, HttpStatusCode } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, defer, from, switchMap, throwError } from 'rxjs';

import { AuthService } from './auth.service';
import { RETRIED_REQUEST, SKIP_AUTH } from './auth.tokens';
import { COOKIE_NAMES, HEADER_NAMES, AUTH_ENDPOINTS } from './cookie.constants';

/**
 * Delay utility that uses setTimeout to ensure we're in a new browser event loop tick.
 * This is more reliable than RxJS delay() for cookie synchronization.
 */
const waitForCookieSync = (ms: number = 300): Promise<void> => {
  return new Promise((resolve) => setTimeout(resolve, ms));
};

/**
 * SECURITY HARDENING (Phase 1): Cookie-Only Authentication Interceptor
 *
 * This interceptor NO LONGER handles token attachment from localStorage.
 * The browser automatically sends HttpOnly cookies with `withCredentials: true`.
 *
 * Responsibilities:
 * 1. Set `withCredentials: true` on all requests (browser sends cookies)
 * 2. Attach XSRF token header for CSRF protection
 * 3. Handle 401 responses → trigger token refresh via HttpOnly refresh cookie
 * 4. Handle session expiry → redirect to login
 */

const shouldBypassAuth = (url: string): boolean =>
  AUTH_ENDPOINTS.some((segment) => url.includes(segment));

/**
 * Enrich request with credentials and XSRF header.
 *
 * SECURITY HARDENING: No longer attaches Authorization header from localStorage.
 * The access token is in an HttpOnly cookie that the browser sends automatically.
 */
const enrichRequest = (request: Parameters<HttpInterceptorFn>[0], markRetried = false) => {
  const context = markRetried ? request.context.set(RETRIED_REQUEST, true) : request.context;

  // Always send credentials (cookies) with requests
  let cloned = request.clone({
    withCredentials: true,
    context,
  });

  // Attach XSRF header for mutation requests (CSRF protection)
  if (shouldAttachXsrfHeader(request)) {
    const xsrfToken = readCookie(COOKIE_NAMES.XSRF_TOKEN);
    if (xsrfToken) {
      cloned = cloned.clone({
        setHeaders: { [HEADER_NAMES.XSRF]: xsrfToken },
      });
    } else {
      // SECURITY: Log warning if XSRF token missing during mutation request
      console.warn(
        `[Security] XSRF token missing for ${request.method} ${request.url}. ` +
          'Ensure CSRF cookie is set by backend on authentication.'
      );
    }
  }

  return cloned;
};

/**
 * Determine if XSRF header should be attached.
 * Required for state-changing requests (POST, PUT, DELETE, PATCH).
 */
const shouldAttachXsrfHeader = (request: Parameters<HttpInterceptorFn>[0]): boolean => {
  // Skip for safe methods (GET, HEAD, OPTIONS)
  if (
    request.method === 'GET' ||
    request.method === 'HEAD' ||
    request.method === 'OPTIONS' ||
    request.headers.has(HEADER_NAMES.XSRF)
  ) {
    return false;
  }

  return typeof document !== 'undefined';
};

/**
 * Read a non-HttpOnly cookie by name.
 * Used for XSRF token (which must be readable by JS for CSRF protection).
 */
const readCookie = (name: string): string | null => {
  if (typeof document === 'undefined') {
    return null;
  }

  const cookies = document.cookie ? document.cookie.split(';') : [];
  for (const cookie of cookies) {
    const [cookieName, ...rest] = cookie.trim().split('=');
    if (cookieName === name) {
      return decodeURIComponent(rest.join('='));
    }
  }

  return null;
};

/**
 * Handle session expiry by clearing state and redirecting to login.
 */
const handleSessionExpiry = (authService: AuthService, router: Router, reason: string) => {
  console.log(`🔒 Session expired: ${reason}`);
  authService.clearSession();
  void router.navigate(['/auth/login'], { queryParams: { session: 'expired' } });
};

/**
 * Cookie-Only Authentication Interceptor
 *
 * SECURITY HARDENING:
 * - Does NOT read tokens from localStorage (eliminated attack surface)
 * - Relies on browser to send HttpOnly cookies automatically
 * - Only handles XSRF header attachment and 401 → refresh flow
 */
export const authTokenInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const skipAuth = req.context.get(SKIP_AUTH) || shouldBypassAuth(req.url);

  return defer(() => {
    // Enrich request with credentials (cookies sent by browser)
    const requestWithAuth = enrichRequest(req);

    return next(requestWithAuth).pipe(
      catchError((error) => {
        // Don't intercept errors on auth endpoints
        if (shouldBypassAuth(req.url) || req.url.includes('/auth/refresh')) {
          return throwError(() => error);
        }

        // Only handle 401 Unauthorized
        if (error.status !== HttpStatusCode.Unauthorized || skipAuth) {
          return throwError(() => error);
        }

        // If already retried, session is truly expired
        if (requestWithAuth.context.get(RETRIED_REQUEST)) {
          handleSessionExpiry(authService, router, `Retry failed for ${req.url}`);
          return throwError(() => error);
        }

        console.log(`🔄 401 on ${req.url} - attempting token refresh...`);

        // Attempt token refresh (uses HttpOnly refresh cookie)
        return authService.refreshAccessToken().pipe(
          switchMap((result) => {
            if (!result) {
              console.log(`❌ Token refresh returned null for ${req.url}`);
              return throwError(() => error);
            }
            console.log(`✅ Token refreshed, waiting 300ms for cookie sync...`);

            // Wait for browser to process Set-Cookie headers in a new event loop tick
            return from(waitForCookieSync(300)).pipe(
              switchMap(() => {
                console.log(`🔄 Retrying ${req.url} after cookie sync delay`);
                const retriedRequest = enrichRequest(req, true);
                return next(retriedRequest).pipe(
                  catchError((retryError) => {
                    if (retryError.status === HttpStatusCode.Unauthorized) {
                      console.error(`❌ Retry still got 401 after refresh - cookie issue`);
                      // Preserve session - user can manually refresh the page
                    }
                    return throwError(() => retryError);
                  })
                );
              })
            );
          }),
          catchError((refreshError) => {
            // Only handle actual refresh endpoint failures
            if (refreshError.url?.includes('/auth/refresh')) {
              console.error(`❌ Refresh endpoint failed:`, refreshError);
              handleSessionExpiry(authService, router, `Refresh failed: ${refreshError.message}`);
            } else {
              // Retry failure - preserve session
              console.warn(`⚠️ Retry failed but session preserved`);
            }
            return throwError(() => refreshError);
          })
        );
      })
    );
  });
};