import { Injectable } from '@angular/core';
import {
  ActivatedRouteSnapshot,
  CanActivate,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';

import { AuthService } from '@core/auth/auth.service';
import { UserRole } from '@core/models/user-role.type';

@Injectable({ providedIn: 'root' })
export class RoleGuard implements CanActivate {
  constructor(private readonly authService: AuthService, private readonly router: Router) {}

  canActivate(route: ActivatedRouteSnapshot, state: RouterStateSnapshot): boolean | UrlTree {
    const expectedRoles = (route.data['roles'] ?? []) as UserRole[];

    if (!this.authService.isAuthenticated()) {
      // Redirect to home page instead of login to provide better UX
      // User can click login button if needed
      return this.router.createUrlTree(['/pocetna']);
    }

    if (!expectedRoles.length || this.authService.hasAnyRole(expectedRoles)) {
      return true;
    }

    return this.router.createUrlTree(['/pocetna']);
  }
}
