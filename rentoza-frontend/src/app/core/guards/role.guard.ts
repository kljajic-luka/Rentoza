import { Injectable } from '@angular/core';
import {
  ActivatedRouteSnapshot,
  CanActivate,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { Observable, map, firstValueFrom } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import { UserRole } from '@core/models/user-role.type';

/**
 * RoleGuard with backend-verified session synchronization.
 *
 * ✅ Backend verification: Uses currentUser$ which reflects /api/users/me response
 * ✅ Role synchronization: Frontend never trusts client-side token claims
 * ✅ Graceful fallback: Redirects unauthorized users to /pocetna
 * ✅ Defense in depth: Guard-level checks backed by backend RLS
 *
 * Usage in routes:
 * {
 *   path: 'owner/dashboard',
 *   canActivate: [RoleGuard],
 *   data: { roles: ['OWNER', 'ADMIN'] }
 * }
 */
@Injectable({ providedIn: 'root' })
export class RoleGuard implements CanActivate {
  constructor(private readonly authService: AuthService, private readonly router: Router) {}

  canActivate(
    route: ActivatedRouteSnapshot,
    state: RouterStateSnapshot
  ): Observable<boolean | UrlTree> | boolean | UrlTree {
    const expectedRoles = (route.data['roles'] ?? []) as UserRole[];

    // ✅ Use currentUser$ observable for reactive backend-verified state
    return this.authService.currentUser$.pipe(
      map((user) => {
        // Not authenticated - redirect to home
        if (!user) {
          console.log('🔒 RoleGuard: User not authenticated - redirecting to login with returnUrl');
          return this.router.createUrlTree(['/auth/login'], {
            queryParams: { returnUrl: state.url }
          });
        }

        // No role requirements - allow access
        if (!expectedRoles.length) {
          return true;
        }

        // ✅ Check backend-verified roles (from /api/users/me, not token claims)
        const hasRequiredRole = user.roles?.some((role) => expectedRoles.includes(role)) ?? false;

        if (hasRequiredRole) {
          return true;
        }

        // Insufficient permissions - redirect to home
        console.log(
          `🚫 RoleGuard: User ${user.email} lacks required roles [${expectedRoles.join(
            ', '
          )}] - has [${user.roles?.join(', ')}]`
        );
        return this.router.createUrlTree(['/pocetna']);
      })
    );
  }
}
