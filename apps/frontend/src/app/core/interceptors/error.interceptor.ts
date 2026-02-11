import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import { ToastService } from '@core/services/toast.service';

/**
 * List of API endpoints that should NOT show error toasts to users.
 * These are typically public endpoints where 401/403 errors are expected for guest users.
 */
const SILENT_ERROR_ENDPOINTS = [
  '/bookings/car/', // Guest users viewing car details shouldn't see auth errors
  '/auth/refresh', // Silent token refresh failures are handled by auth service
  '/availability', // Availability checks are non-critical and handled in component
  '/stats', // Stats endpoints are non-blocking
  '/reviews', // Review loading is non-blocking
];

/**
 * HTTP status codes that should be handled silently (no toast)
 * when accessing public resources as a guest user.
 */
const SILENT_ERROR_CODES = [401, 403];

export const errorResponseInterceptor: HttpInterceptorFn = (req, next) => {
  const toast = inject(ToastService);
  const authService = inject(AuthService);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      const shouldShowToast = shouldDisplayError(req.url, error.status);

      // Only show toast for actual errors (status >= 400), not for connection issues during navigation
      // Suppress errors for requests that are likely stale (e.g., from previous page navigation)
      if (shouldShowToast && error.status >= 400) {
        const message = extractUserFriendlyMessage(error);
        toast.error(message);
      }

      // ✅ Handle 401 (Unauthorized) - token expired or invalid
      if (error.status === 401 && !isSilentEndpoint(req.url)) {
        // CRITICAL FIX: Only clear session if critical auth endpoints fail
        // This prevents auto-logout when a single business endpoint fails (e.g. /bookings/pending)
        if (req.url.includes('/users/me') || req.url.includes('/auth/')) {
          console.log('🔒 401 Unauthorized on critical endpoint - clearing session');
          authService.clearSession();
        } else {
          console.warn('🔒 401 Unauthorized on business endpoint - preserving session state');
        }
        // Don't show toast - token interceptor handles refresh
      }

      // ✅ Handle 403 (Forbidden) - insufficient permissions or RLS violation
      if (error.status === 403 && !isSilentEndpoint(req.url)) {
        console.log('🚫 403 Forbidden - RLS enforcement triggered');

        // Show user-friendly message for permission denials
        if (shouldShowToast) {
          toast.warning('Nemate dozvolu za pristup ovom resursu.');
        }

        // Clear session if 403 indicates invalid authentication state
        // (e.g., user role changed, account disabled)
        if (req.url.includes('/users/me') || req.url.includes('/auth/')) {
          console.log('🔒 403 on auth endpoint - clearing session');
          authService.clearSession();
        }
      }

      return throwError(() => error);
    }),
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
  return SILENT_ERROR_ENDPOINTS.some((endpoint) => url.includes(endpoint));
}

/**
 * Extracts a user-friendly error message from the HTTP error response.
 * Only shows generic messages - no technical details.
 */
function extractUserFriendlyMessage(error: HttpErrorResponse): string {
  // Check for specific error codes from backend
  const errorCode = error.error?.code;

  // Handle specific booking conflict types
  if (error.status === 409) {
    // Check-in timing blocked (photo upload response uses errorCodes array)
    const errorCodes: string[] | undefined = error.error?.errorCodes;
    if (errorCodes?.includes('CHECKIN_TOO_EARLY')) {
      const earliest = error.error?.earliestAllowedTime;
      const minutes = error.error?.minutesUntilAllowed;
      if (earliest) {
        const time = earliest.substring(11, 16); // extract HH:mm from ISO local
        return `Check-in nije još dozvoljen. Pokušajte ponovo u ${time} (za ${minutes ?? '?'} min).`;
      }
      return error.error?.userMessage || 'Check-in još nije dozvoljen.';
    }

    switch (errorCode) {
      case 'USER_OVERLAP':
        return 'Ne možete rezervisati dva vozila u isto vreme. Već imate aktivnu ili čekajuću rezervaciju za ovaj period.';
      case 'CAR_UNAVAILABLE':
        return 'Ovaj automobil je već rezervisan za izabrane datume. Molimo izaberite druge datume.';
      case 'STALE_DATA':
        return 'Podaci su izmenjeni od strane drugog korisnika. Molimo osvežite stranicu i pokušajte ponovo.';
      case 'DB_DEADLOCK':
        return 'Server je privremeno zauzet. Molimo pokušajte ponovo.';
      default:
        return (
          error.error?.message || 'Ova radnja nije moguća zbog konflikta sa trenutnim stanjem.'
        );
    }
  }

  // For specific error codes, show user-friendly messages
  switch (error.status) {
    case 0:
      return 'Nemoguće povezivanje sa serverom. Proverite vašu internet konekciju.';
    case 404:
      return 'Traženi resurs nije pronađen.';
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
