import { inject } from '@angular/core';
import { Router, CanActivateFn } from '@angular/router';
import { map } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';

/**
 * Guard that only allows USER (renter) role to access routes.
 * Redirects owners to owner dashboard and guests to login.
 */
export const renterGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  return authService.currentUser$.pipe(
    map((user) => {
      if (!user) {
        // Not logged in - redirect to login
        return router.createUrlTree(['/auth/login']);
      }

      const isRenter = user.roles?.includes('USER');
      const isOwner = user.roles?.includes('OWNER');

      // Owners have priority - redirect to owner dashboard
      if (isOwner) {
        return router.createUrlTree(['/owner']);
      }

      if (!isRenter) {
        // No valid role - redirect to home
        return router.createUrlTree(['/']);
      }

      // User is a renter - allow access
      return true;
    })
  );
};
