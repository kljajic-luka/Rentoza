import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';

import { UserProfile } from '@core/models/user.model';

/**
 * Service responsible for role-based redirection after login.
 * Ensures users are directed to their appropriate dashboard based on their role.
 */
@Injectable({ providedIn: 'root' })
export class RedirectService {
  private readonly router = inject(Router);

  /**
   * Redirects user to their role-specific dashboard after login.
   * Priority: OWNER > USER > default (home)
   */
  redirectAfterLogin(user: UserProfile): void {
    const targetRoute = this.getDefaultRoute(user);
    void this.router.navigate([targetRoute]);
  }

  /**
   * Get the default route for a user based on their role.
   * Owners have priority if user has multiple roles.
   */
  getDefaultRoute(user: UserProfile | null): string {
    if (!user || !user.roles || user.roles.length === 0) {
      return '/';
    }

    // Admins have highest priority
    if (user.roles.includes('ADMIN')) {
      return '/admin/dashboard';
    }

    // Owners (hosts) have priority - they go to owner dashboard
    if (user.roles.includes('OWNER')) {
      return '/owner';
    }

    // Users with USER role stay on homepage
    if (user.roles.includes('USER')) {
      return '/';
    }

    // Admin or other roles default to home
    return '/';
  }

  /**
   * Check if a user is an owner
   */
  isOwner(user: UserProfile | null): boolean {
    return user?.roles?.includes('OWNER') ?? false;
  }

  /**
   * Check if a user is a renter
   */
  isRenter(user: UserProfile | null): boolean {
    return user?.roles?.includes('USER') ?? false;
  }

  /**
   * Get user's primary role for display purposes
   */
  getPrimaryRole(user: UserProfile | null): 'owner' | 'renter' | 'guest' {
    if (!user) {
      return 'guest';
    }

    if (user.roles?.includes('OWNER')) {
      return 'owner';
    }

    if (user.roles?.includes('USER')) {
      return 'renter';
    }

    return 'guest';
  }
}