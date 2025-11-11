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
 * Guard that redirects users to role-appropriate routes with backend-verified roles.
 *
 * ✅ Backend synchronization: Uses currentUser$ from /api/users/me verification
 * ✅ Graceful 403 handling: Redirects unauthorized users to appropriate pages
 * ✅ Role-based routing:
 *   - OWNER users trying to access renter routes (/pocetna, /vozila, /bookings, /favorites)
 *     are redirected to /owner/dashboard
 *   - USER users trying to access owner routes (/owner/**)
 *     are redirected to /pocetna
 * ✅ Session awareness: Redirects to /pocetna on session expiration
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
        const currentPath = state.url;

        // ✅ Not authenticated - apply public route logic
        if (!user) {
          // Allow public routes (home, cars, auth pages)
          if (
            currentPath === '/' ||
            currentPath === '/pocetna' ||
            currentPath.startsWith('/vozila') ||
            currentPath.startsWith('/cars') ||
            currentPath.startsWith('/auth')
          ) {
            return true;
          }

          // ✅ Redirect to home for protected routes when not authenticated (403 handling)
          console.log('🔒 RoleRedirectGuard: User not authenticated - redirecting to /pocetna');
          return this.router.createUrlTree(['/pocetna']);
        }

        // ✅ Check backend-verified roles
        const isOwner = user.roles?.includes('OWNER') ?? false;
        const isUser = user.roles?.includes('USER') ?? false;

        // ✅ Owner trying to access renter-only routes - redirect to owner dashboard
        if (isOwner && this.isRenterOnlyRoute(currentPath)) {
          console.log(
            '🚫 RoleRedirectGuard: OWNER accessing renter route - redirecting to /owner/dashboard'
          );
          return this.router.createUrlTree(['/owner/dashboard']);
        }

        // ✅ Regular user trying to access owner routes - redirect to home
        if (isUser && !isOwner && currentPath.startsWith('/owner')) {
          console.log('🚫 RoleRedirectGuard: USER accessing owner route - redirecting to /pocetna');
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
