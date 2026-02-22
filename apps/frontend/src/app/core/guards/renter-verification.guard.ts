import { Injectable, inject } from '@angular/core';
import {
  CanActivate,
  ActivatedRouteSnapshot,
  RouterStateSnapshot,
  Router,
  UrlTree,
} from '@angular/router';
import { Observable, of } from 'rxjs';
import { map, catchError, take, switchMap } from 'rxjs/operators';

import { RenterVerificationService } from '@core/services/renter-verification.service';
import { AuthService } from '@core/auth/auth.service';
import { isApproved } from '@core/models/renter-verification.model';

/**
 * Guard to protect booking routes requiring verified renter license.
 *
 * Usage in routes:
 * ```typescript
 * {
 *   path: 'cars/:id/book',
 *   canActivate: [RoleGuard, RenterVerificationGuard],
 *   loadComponent: () => import('./booking.component').then(m => m.BookingComponent),
 * }
 * ```
 *
 * Behavior:
 * - Checks if user has verified driver's license (APPROVED status)
 * - If not verified: redirects to /verify-license with returnUrl
 * - If verified: allows navigation to proceed
 * - If user not logged in: defers to AuthGuard/RoleGuard
 *
 * NOTE: This is a client-side convenience check only.
 * Final eligibility is enforced in BookingService.createBooking() on backend.
 */
@Injectable({ providedIn: 'root' })
export class RenterVerificationGuard implements CanActivate {
  private readonly router = inject(Router);
  private readonly authService = inject(AuthService);
  private readonly verificationService = inject(RenterVerificationService);

  canActivate(
    route: ActivatedRouteSnapshot,
    state: RouterStateSnapshot
  ): Observable<boolean | UrlTree> {
    // First check if user is authenticated
    return this.authService.currentUser$.pipe(
      take(1),
      switchMap((user) => {
        // If not logged in, let other guards handle redirect
        if (!user) {
          return of(true);
        }

        // Check verification status
        return this.checkVerificationStatus(state.url);
      })
    );
  }

  /**
   * Check user's verification status and redirect if not approved.
   */
  private checkVerificationStatus(returnUrl: string): Observable<boolean | UrlTree> {
    // Load status if not already loaded
    this.verificationService.loadStatus();

    return this.verificationService.status$.pipe(
      take(1),
      map((status) => {
        // If status not loaded yet, allow navigation (backend will catch it)
        if (!status) {
          return true;
        }

        // If approved, allow navigation
        if (isApproved(status.status)) {
          return true;
        }

        // If not approved, redirect to verification page
        console.log(
          `🔒 RenterVerificationGuard: License status is ${status.status}, redirecting to verification`
        );

        return this.router.createUrlTree(['/verify-license'], {
          queryParams: {
            returnUrl,
            reason: this.getRedirectReason(status.status),
          },
        });
      }),
      catchError(() => {
        // On error, allow navigation (backend will enforce)
        return of(true);
      })
    );
  }

  /**
   * Get user-friendly reason for redirect.
   */
  private getRedirectReason(status: string): string {
    switch (status) {
      case 'NOT_STARTED':
        return 'verification_required';
      case 'PENDING_REVIEW':
        return 'verification_pending';
      case 'REJECTED':
        return 'verification_rejected';
      case 'EXPIRED':
        return 'license_expired';
      case 'SUSPENDED':
        return 'account_suspended';
      default:
        return 'verification_required';
    }
  }
}