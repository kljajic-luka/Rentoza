import { inject } from '@angular/core';
import { Router, CanActivateFn } from '@angular/router';
import { map } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';

/**
 * Guard that only allows OWNER role to access routes.
 * Redirects renters to renter dashboard and guests to login.
 */
export const ownerGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  return authService.currentUser$.pipe(
    map((user) => {
      if (!user) {
        // Not logged in - redirect to login
        return router.createUrlTree(['/auth/login']);
      }

      const isOwner = user.roles?.includes('OWNER');
      if (!isOwner) {
        // Logged in but not an owner - redirect to renter dashboard
        return router.createUrlTree(['/renter']);
      }

      // User is an owner - allow access
      return true;
    })
  );
};
