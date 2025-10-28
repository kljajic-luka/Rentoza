import { HttpInterceptorFn, HttpStatusCode } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, defer, switchMap, throwError } from 'rxjs';

import { AuthService } from './auth.service';
import { RETRIED_REQUEST, SKIP_AUTH } from './auth.tokens';

const AUTH_EXCLUDE_SEGMENTS = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/logout'];

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
    context
  });

  if (!skipAuth && token) {
    cloned = cloned.clone({
      setHeaders: { Authorization: `Bearer ${token}` }
    });
  }

  return cloned;
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
