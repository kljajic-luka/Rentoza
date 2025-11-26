import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map, take } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';

/**
 * GuestGuard (Reverse Auth Guard)
 *
 * Blocks authenticated users from accessing guest-only routes like /login and /register.
 * Improves UX by preventing logged-in users from seeing login forms.
 *
 * BEHAVIOR:
 * - IF Logged In:
 *   - OWNER/ADMIN → redirect to /owner/dashboard
 *   - USER → redirect to /cars
 *   - Default → redirect to /
 * - IF NOT Logged In: Allow access to the route
 *
 * USAGE:
 * {
 *   path: 'auth/login',
 *   canActivate: [guestGuard],
 *   loadComponent: () => import('...').then(m => m.LoginComponent)
 * }
 */
export const guestGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  return authService.currentUser$.pipe(
    take(1),
    map((user) => {
      // Not authenticated - allow access to guest routes
      if (!user) {
        return true;
      }

      // User is authenticated - redirect based on role
      console.log(`🔄 GuestGuard: Authenticated user (${user.email}) blocked from guest route`);

      // Determine redirect destination based on role
      const redirectUrl = getRedirectUrlForUser(user.roles ?? []);
      console.log(`➡️ Redirecting to: ${redirectUrl}`);

      return router.createUrlTree([redirectUrl]);
    })
  );
};

/**
 * Determines the appropriate redirect URL based on user roles.
 *
 * Priority:
 * 1. OWNER or ADMIN → /owner/dashboard (owner management area)
 * 2. USER → /cars (car browsing for renters)
 * 3. Default → / (home page)
 */
function getRedirectUrlForUser(roles: string[]): string {
  // Owners and Admins go to owner dashboard
  if (roles.includes('OWNER') || roles.includes('ADMIN')) {
    return '/owner/dashboard';
  }

  // Regular users go to car listings
  if (roles.includes('USER')) {
    return '/cars';
  }

  // Fallback to home
  return '/';
}
