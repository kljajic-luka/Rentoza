import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { ToastrService } from 'ngx-toastr';
import { catchError, throwError } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';

/**
 * List of API endpoints that should NOT show error toasts to users.
 * These are typically public endpoints where 401/403 errors are expected for guest users.
 */
const SILENT_ERROR_ENDPOINTS = [
  '/bookings/car/',  // Guest users viewing car details shouldn't see auth errors
  '/auth/refresh',   // Silent token refresh failures are handled by auth service
];

/**
 * HTTP status codes that should be handled silently (no toast)
 * when accessing public resources as a guest user.
 */
const SILENT_ERROR_CODES = [401, 403];

export const errorResponseInterceptor: HttpInterceptorFn = (req, next) => {
  const toastr = inject(ToastrService);
  const authService = inject(AuthService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      const shouldShowToast = shouldDisplayError(req.url, error.status);

      if (shouldShowToast) {
        const message = extractUserFriendlyMessage(error);
        toastr.error(message, 'Greška');
      }

      // Only logout on 401 if it's from an authenticated endpoint
      // (not from public endpoints like viewing car details as guest)
      if (error.status === 401 && !isSilentEndpoint(req.url)) {
        authService.logout();
      }

      return throwError(() => error);
    })
  );
};

/**
 * Determines if an error should be displayed to the user.
 */
function shouldDisplayError(url: string, status: number): boolean {
  // Silent endpoints should never show errors
  if (isSilentEndpoint(url)) {
    return false;
  }

  // For other endpoints, only show errors for certain status codes
  // Don't show 401/403 errors - these are expected for guest users
  if (SILENT_ERROR_CODES.includes(status)) {
    return false;
  }

  // Show all other errors (500, 404, network errors, etc.)
  return true;
}

/**
 * Checks if a URL matches any silent endpoint pattern.
 */
function isSilentEndpoint(url: string): boolean {
  return SILENT_ERROR_ENDPOINTS.some(endpoint => url.includes(endpoint));
}

/**
 * Extracts a user-friendly error message from the HTTP error response.
 * Only shows generic messages - no technical details.
 */
function extractUserFriendlyMessage(error: HttpErrorResponse): string {
  // For specific error codes, show user-friendly messages
  switch (error.status) {
    case 0:
      return 'Nemoguće povezivanje sa serverom. Proverite vašu internet konekciju.';
    case 404:
      return 'Traženi resurs nije pronađen.';
    case 409:
      return 'Ova radnja nije moguća zbog konflikta sa trenutnim stanjem.';
    case 422:
      return 'Podaci koje ste uneli nisu validni.';
    case 500:
    case 502:
    case 503:
      return 'Servis je trenutno nedostupan. Molimo pokušajte kasnije.';
    default:
      return 'Došlo je do greške. Molimo pokušajte ponovo.';
  }
}
