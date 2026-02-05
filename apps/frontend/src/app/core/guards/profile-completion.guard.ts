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

import { AuthService } from '@core/auth/auth.service';
import { EnhancedUserProfile, RegistrationStatus } from '@core/models/auth.model';

/**
 * Profile Completion Guard - Enterprise-Grade Progressive Protection
 *
 * This guard implements a "soft prompt, hard guard" pattern for Google OAuth users:
 *
 * **Soft Prompt (Auth Callback):**
 * - After OAuth login, if registrationStatus = INCOMPLETE, redirect to /auth/complete-profile
 * - User CAN skip this and browse the platform
 *
 * **Hard Guard (This Guard):**
 * - Blocks critical actions (booking, listing cars, payments) if profile is incomplete
 * - Redirects to /auth/complete-profile with returnUrl
 * - Shows toast notification explaining why access was blocked
 *
 * **Usage in routes:**
 * ```typescript
 * {
 *   path: 'cars/:id/book',
 *   canActivate: [RoleGuard, ProfileCompletionGuard],
 *   loadComponent: () => import('./booking.component').then(m => m.BookingComponent),
 * }
 * ```
 *
 * **Protected Routes (add this guard):**
 * - `/cars/:id/book` - Cannot book without complete profile
 * - `/owner/cars/add` - Cannot list vehicles without complete profile
 * - `/payments/**` - Cannot access payment features
 * - `/bookings/confirm` - Cannot confirm bookings
 *
 * **Why this pattern:**
 * - Users can browse, search, view cars without completing profile
 * - Only critical actions require profile completion
 * - Reduces friction for new users while ensuring data integrity for transactions
 *
 * @see REGISTRATION_IMPLEMENTATION_PLAN.md lines 672-917
 */
@Injectable({ providedIn: 'root' })
export class ProfileCompletionGuard implements CanActivate {
  private readonly router = inject(Router);
  private readonly authService = inject(AuthService);

  canActivate(
    route: ActivatedRouteSnapshot,
    state: RouterStateSnapshot,
  ): Observable<boolean | UrlTree> {
    return this.authService.currentUser$.pipe(
      take(1),
      switchMap((user) => {
        // If not logged in, let other guards handle redirect
        if (!user) {
          return of(true);
        }

        return this.checkProfileCompletion(user as EnhancedUserProfile, state.url);
      }),
    );
  }

  /**
   * Check if user's profile is complete.
   * If not, redirect to profile completion page with return URL.
   */
  private checkProfileCompletion(
    user: EnhancedUserProfile,
    returnUrl: string,
  ): Observable<boolean | UrlTree> {
    // Check registration status
    const status = user.registrationStatus;

    // If status is ACTIVE or undefined (legacy users), allow access
    if (!status || status === 'ACTIVE') {
      return of(true);
    }

    // If INCOMPLETE, redirect to profile completion
    if (status === 'INCOMPLETE') {
      console.warn('🛡️ [ProfileCompletionGuard] Blocking access - profile incomplete:', returnUrl);

      // Determine role parameter for the complete-profile page
      const roleParam = user.roles?.includes('OWNER') ? 'OWNER' : 'USER';

      // Build redirect URL with return path
      const redirectUrl = this.router.createUrlTree(['/auth/complete-profile'], {
        queryParams: {
          returnUrl: returnUrl,
          role: roleParam,
        },
      });

      return of(redirectUrl);
    }

    // For SUSPENDED or other statuses, let the route proceed
    // (other guards or the page itself should handle these cases)
    return of(true);
  }
}

/**
 * Functional guard variant for modern Angular (standalone components).
 *
 * Usage:
 * ```typescript
 * import { profileCompletionGuard } from '@core/guards/profile-completion.guard';
 *
 * {
 *   path: 'cars/:id/book',
 *   canActivate: [profileCompletionGuard],
 *   loadComponent: () => ...
 * }
 * ```
 */
export const profileCompletionGuard = (
  route: ActivatedRouteSnapshot,
  state: RouterStateSnapshot,
): Observable<boolean | UrlTree> => {
  return inject(ProfileCompletionGuard).canActivate(route, state);
};
