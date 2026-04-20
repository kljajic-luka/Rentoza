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
 * Enterprise-grade guard that enforces strict role-based route separation.
 *
 * ✅ ADMIN ISOLATION: Admin users can ONLY access /admin/** routes.
 *    Attempting to access any other route redirects to /admin/dashboard.
 *    This is a security feature to prevent admins from accidentally
 *    interacting with the platform as regular users.
 *
 * ✅ OWNER/USER separation: Owners see owner dashboard, users see homepage.
 * ✅ Backend synchronization: Uses currentUser$ from verified /api/users/me
 * ✅ Graceful fallback: Redirects unauthorized users appropriately
 */
@Injectable({ providedIn: 'root' })
export class RoleRedirectGuard implements CanActivate {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  // Routes forbidden for OWNER users (renter-only)
  private readonly renterOnlyPaths = ['/pocetna', '/vozila', '/cars', '/favorites'];

  // Booking list route (renter-only, but detail/check-in are shared)
  private readonly bookingsListPath = '/bookings';

  // Admin-only routes (admin can ONLY access these)
  // /messages included for admin dispute transcript review
  private readonly adminAllowedPrefixes = ['/admin', '/auth', '/messages'];

  canActivate(
    route: ActivatedRouteSnapshot,
    state: RouterStateSnapshot,
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

          // Redirect to home for protected routes when not authenticated
          console.log('🔒 RoleRedirectGuard: User not authenticated - redirecting to /pocetna');
          return this.router.createUrlTree(['/pocetna']);
        }

        // ============================================================
        // ADMIN ISOLATION - Enterprise Security Feature
        // Admin users can ONLY access /admin/** routes. This prevents:
        // - Accidental data manipulation as a "regular user"
        // - UI confusion between admin and user experiences
        // - Security risks from admin accessing user-facing features
        // ============================================================
        const isAdmin = user.roles?.includes('ADMIN') ?? false;

        if (isAdmin) {
          // Admin accessing allowed routes (admin panel, auth for logout)
          const isAdminAllowedRoute = this.adminAllowedPrefixes.some(
            (prefix) => currentPath === prefix || currentPath.startsWith(prefix + '/'),
          );

          if (isAdminAllowedRoute) {
            console.log('✅ RoleRedirectGuard: ADMIN accessing admin route - allowing');
            return true;
          }

          // Admin trying to access ANY non-admin route - redirect to admin dashboard
          console.log(
            `🚫 RoleRedirectGuard: ADMIN accessing non-admin route "${currentPath}" - forcing to /admin/dashboard`,
          );
          return this.router.createUrlTree(['/admin/dashboard']);
        }

        // ============================================================
        // NON-ADMIN users cannot access /admin routes
        // ============================================================
        if (!isAdmin && currentPath.startsWith('/admin')) {
          console.log(
            '🚫 RoleRedirectGuard: Non-admin user accessing /admin - redirecting to /pocetna',
          );
          return this.router.createUrlTree(['/pocetna']);
        }

        // ============================================================
        // OWNER/USER Role Separation
        // ============================================================
        const isOwner = user.roles?.includes('OWNER') ?? false;
        const isUser = user.roles?.includes('USER') ?? false;

        // Owner trying to access renter-only routes - redirect to owner dashboard
        if (isOwner && this.isRenterOnlyRoute(currentPath)) {
          console.log(
            '🚫 RoleRedirectGuard: OWNER accessing renter route - redirecting to /owner/dashboard',
          );
          return this.router.createUrlTree(['/owner/dashboard']);
        }

        // Regular user trying to access owner routes - redirect to home
        if (isUser && !isOwner && currentPath.startsWith('/owner')) {
          console.log('🚫 RoleRedirectGuard: USER accessing owner route - redirecting to /pocetna');
          return this.router.createUrlTree(['/pocetna']);
        }

        // Allow access
        return true;
      }),
    );
  }

  private isRenterOnlyRoute(path: string): boolean {
    // Check if it's a booking detail or check-in route (shared between owner and renter)
    // Pattern: /bookings/:id or /bookings/:id/check-in or /bookings/:id/anything
    const bookingDetailPattern = /^\/bookings\/\d+/;
    if (bookingDetailPattern.test(path)) {
      return false; // Booking detail and check-in routes are accessible by both roles
    }

    // /bookings (list) is renter-only
    if (path === this.bookingsListPath || path === this.bookingsListPath + '/') {
      return true;
    }

    // Root path is renter-only (Owners should be on dashboard)
    if (path === '/') {
      return true;
    }

    // Check other renter-only paths
    return this.renterOnlyPaths.some(
      (renterPath) => path === renterPath || path.startsWith(renterPath + '/'),
    );
  }
}