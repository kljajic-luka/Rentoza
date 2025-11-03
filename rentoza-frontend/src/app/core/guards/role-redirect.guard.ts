import { Injectable, inject } from '@angular/core';
import {
  CanActivate,
  Router,
  ActivatedRouteSnapshot,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { map, Observable } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';

/**
 * Guard that redirects users to role-appropriate routes.
 *
 * - OWNER users trying to access renter routes (/pocetna, /vozila, /bookings, /favorites)
 *   are redirected to /owner/dashboard
 * - USER users trying to access owner routes (/owner/**)
 *   are redirected to /pocetna
 */
@Injectable({ providedIn: 'root' })
export class RoleRedirectGuard implements CanActivate {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  // Routes forbidden for OWNER users (renter-only)
  private readonly renterOnlyPaths = ['/pocetna', '/vozila', '/cars', '/bookings', '/favorites'];

  canActivate(
    route: ActivatedRouteSnapshot,
    state: RouterStateSnapshot
  ): Observable<boolean | UrlTree> {
    return this.authService.currentUser$.pipe(
      map((user) => {
        // If not authenticated, allow (will be handled by auth guard)
        if (!user) {
          return true;
        }

        const currentPath = state.url;
        const isOwner = user.roles?.includes('OWNER') ?? false;
        const isUser = user.roles?.includes('USER') ?? false;

        // Owner trying to access renter-only routes
        if (isOwner && this.isRenterOnlyRoute(currentPath)) {
          return this.router.createUrlTree(['/owner/dashboard']);
        }

        // Regular user trying to access owner routes
        if (isUser && !isOwner && currentPath.startsWith('/owner')) {
          return this.router.createUrlTree(['/pocetna']);
        }

        // Allow access
        return true;
      })
    );
  }

  private isRenterOnlyRoute(path: string): boolean {
    return this.renterOnlyPaths.some(
      (renterPath) => path === renterPath || path.startsWith(renterPath + '/')
    );
  }
}
