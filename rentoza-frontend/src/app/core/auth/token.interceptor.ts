import { HttpInterceptorFn, HttpStatusCode } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, defer, switchMap, throwError } from 'rxjs';

import { environment } from '@environments/environment';
import { AuthService } from './auth.service';
import { RETRIED_REQUEST, SKIP_AUTH } from './auth.tokens';
import { COOKIE_NAMES, HEADER_NAMES, AUTH_ENDPOINTS } from './cookie.constants';

const ABSOLUTE_URL_REGEX = /^https?:\/\//i;

const shouldBypassAuth = (url: string): boolean =>
  AUTH_ENDPOINTS.some((segment) => url.includes(segment));

const enrichRequest = (
  request: Parameters<HttpInterceptorFn>[0],
  token: string | null,
  skipAuth: boolean,
  markRetried = false
) => {
  const context = markRetried ? request.context.set(RETRIED_REQUEST, true) : request.context;
  let cloned = request.clone({
    withCredentials: true,
    context,
  });

  const headersToApply: Record<string, string> = {};

  const shouldAttachAuthHeader =
    !skipAuth && token && (!environment.auth?.useCookies || isCrossOriginRequest(request.url));

  if (shouldAttachAuthHeader) {
    headersToApply[HEADER_NAMES.AUTHORIZATION] = `Bearer ${token}`;
  }

  if (shouldAttachXsrfHeader(request)) {
    const xsrfToken = readCookie(COOKIE_NAMES.XSRF_TOKEN);
    if (xsrfToken) {
      headersToApply[HEADER_NAMES.XSRF] = xsrfToken;
    } else {
      // SECURITY: Log warning if XSRF token missing during mutation request
      // This could indicate a misconfiguration or attack
      console.warn(
        `[Security] XSRF token missing for ${request.method} ${request.url}. ` +
          'Ensure CSRF cookie is set by backend on authentication.'
      );
    }
  }

  if (Object.keys(headersToApply).length > 0) {
    cloned = cloned.clone({ setHeaders: headersToApply });
  }

  return cloned;
};

const isCrossOriginRequest = (url: string): boolean => {
  if (!ABSOLUTE_URL_REGEX.test(url) || typeof window === 'undefined') {
    return false;
  }

  try {
    const target = new URL(url);
    return target.origin !== window.location.origin;
  } catch (error) {
    // If URL parsing fails, fall back to treating it as same-origin to avoid false positives
    return false;
  }
};

const shouldAttachXsrfHeader = (request: Parameters<HttpInterceptorFn>[0]): boolean => {
  if (!environment.auth?.useCookies) {
    return false;
  }

  if (
    request.method === 'GET' ||
    request.method === 'HEAD' ||
    request.headers.has(HEADER_NAMES.XSRF)
  ) {
    return false;
  }

  return typeof document !== 'undefined';
};

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

const handleSessionExpiry = (authService: AuthService, router: Router) => {
  authService.clearSession();
  void router.navigate(['/auth/login'], { queryParams: { session: 'expired' } });
};

export const authTokenInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const skipAuth = req.context.get(SKIP_AUTH) || shouldBypassAuth(req.url);

  return defer(() => {
    const token = authService.getAccessToken();
    const requestWithAuth = enrichRequest(req, token, skipAuth);

    return next(requestWithAuth).pipe(
      catchError((error) => {
        if (shouldBypassAuth(req.url) || req.url.includes('/auth/refresh')) {
          return throwError(() => error);
        }

        if (error.status !== HttpStatusCode.Unauthorized || skipAuth) {
          return throwError(() => error);
        }

        if (requestWithAuth.context.get(RETRIED_REQUEST)) {
          handleSessionExpiry(authService, router);
          return throwError(() => error);
        }

        return authService.refreshAccessToken().pipe(
          switchMap((newToken) => {
            if (!newToken) {
              return throwError(() => error);
            }
            const retriedRequest = enrichRequest(req, newToken, skipAuth, true);
            return next(retriedRequest);
          }),
          catchError((refreshError) => {
            handleSessionExpiry(authService, router);
            return throwError(() => refreshError);
          })
        );
      })
    );
  });
};
