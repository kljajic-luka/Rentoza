import { HttpInterceptorFn, HttpStatusCode } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, defer, switchMap, throwError } from 'rxjs';

import { environment } from '@environments/environment';
import { AuthService } from './auth.service';
import { RETRIED_REQUEST, SKIP_AUTH } from './auth.tokens';

const AUTH_EXCLUDE_SEGMENTS = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/logout'];
const ABSOLUTE_URL_REGEX = /^https?:\/\//i;
const XSRF_COOKIE_NAME = 'XSRF-TOKEN';
const XSRF_HEADER_NAME = 'X-XSRF-TOKEN';

const shouldBypassAuth = (url: string): boolean =>
  AUTH_EXCLUDE_SEGMENTS.some((segment) => url.includes(segment));

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
    headersToApply['Authorization'] = `Bearer ${token}`;
  }

  if (shouldAttachXsrfHeader(request)) {
    const xsrfToken = readCookie(XSRF_COOKIE_NAME);
    if (xsrfToken) {
      headersToApply[XSRF_HEADER_NAME] = xsrfToken;
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
    request.headers.has(XSRF_HEADER_NAME)
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
