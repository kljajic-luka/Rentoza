import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { finalize } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import { RedirectService } from '@core/services/redirect.service';
import { FavoriteService } from '@core/services/favorite.service';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';

/**
 * Component that handles OAuth2 callback from Google authentication.
 *
 * Flow:
 * 1. Google redirects to /auth/callback?token=JWT_TOKEN
 * 2. Component extracts token from query params
 * 3. Injects token into AuthService (same as local login)
 * 4. Fetches user profile
 * 5. Redirects to appropriate page based on user role
 */
@Component({
  selector: 'app-auth-callback',
  standalone: true,
  imports: [CommonModule, MatCardModule, MatProgressSpinnerModule, MatIconModule],
  templateUrl: './auth-callback.component.html',
  styleUrls: ['./auth-callback.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthCallbackComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly authService = inject(AuthService);
  private readonly redirectService = inject(RedirectService);
  private readonly toastr = inject(ToastrService);
  private readonly favoriteService = inject(FavoriteService);

  protected readonly isProcessing = signal(true);
  protected readonly errorMessage = signal<string | null>(null);

  ngOnInit(): void {
    this.handleOAuth2Callback();
  }

  private handleOAuth2Callback(): void {
    // Extract token and error from query params
    const token = this.route.snapshot.queryParamMap.get('token');
    const error = this.route.snapshot.queryParamMap.get('error');

    // Handle OAuth2 error
    if (error) {
      console.error('OAuth2 authentication failed:', error);
      this.errorMessage.set('Google prijavljivanje nije uspelo. Pokušajte ponovo.');
      this.isProcessing.set(false);

      this.toastr.error('Google prijavljivanje nije uspelo', 'Greška');

      // Redirect to login after 2 seconds
      setTimeout(() => {
        void this.router.navigate(['/auth/login'], {
          queryParams: { error: 'google_auth_failed' },
        });
      }, 2000);
      return;
    }

    // Handle missing token
    if (!token) {
      console.error('No token received from OAuth2 callback');
      this.errorMessage.set('Nije primljen token autentifikacije.');
      this.isProcessing.set(false);

      this.toastr.error('Nije primljen token autentifikacije', 'Greška');

      // Redirect to login after 2 seconds
      setTimeout(() => {
        void this.router.navigate(['/auth/login'], {
          queryParams: { error: 'no_token' },
        });
      }, 2000);
      return;
    }

    // Process successful OAuth2 authentication
    this.processGoogleToken(token);
  }

  private async processGoogleToken(token: string): Promise<void> {
    try {
      // ✅ STEP 1: Persist token to localStorage via AuthService
      // This ensures the token survives page reloads
      console.log('🔐 OAuth2: Persisting access token to localStorage');
      this.authService.setAccessToken(token);

      // ✅ STEP 2: Verify session with backend /api/users/me
      // This ensures frontend state synchronizes with backend RLS enforcement
      console.log('🔄 OAuth2: Verifying session with backend /api/users/me');
      const verifiedUser = await this.authService.verifySession();

      if (!verifiedUser) {
        throw new Error('Session verification failed - backend returned null');
      }

      // ✅ STEP 3: Load user's favorited cars for immediate availability
      console.log('❤️ OAuth2: Loading favorited cars');
      this.favoriteService.loadFavoritedCarIds().subscribe({
        error: (err) => console.warn('Failed to preload favorites after OAuth login', err),
      });

      // ✅ STEP 4: Start token expiration watcher
      console.log('⏰ OAuth2: Starting token expiration watcher');
      this.authService.startTokenWatcher(60000);

      // ✅ STEP 5: Show success notification
      this.toastr.success('Uspešno ste se prijavili putem Google naloga!', 'Dobrodošli');

      // ✅ STEP 6: Role-based redirection using backend-verified roles
      const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');

      if (returnUrl) {
        console.log(`🔀 OAuth2: Redirecting to return URL: ${returnUrl}`);
        void this.router.navigateByUrl(returnUrl);
      } else {
        console.log(
          `🔀 OAuth2: Redirecting based on verified role: ${verifiedUser.roles?.join(', ')}`
        );
        this.redirectToRoleBasedDashboard(verifiedUser.roles);
      }

      this.isProcessing.set(false);
    } catch (err) {
      console.error('❌ OAuth2: Session verification failed:', err);
      this.errorMessage.set('Neuspešno učitavanje korisničkog profila.');
      this.isProcessing.set(false);

      this.toastr.error('Neuspešno učitavanje profila', 'Greška');

      // Clear the session and redirect to login
      this.authService.clearSession();

      setTimeout(() => {
        void this.router.navigate(['/auth/login'], {
          queryParams: { error: 'profile_load_failed' },
        });
      }, 2000);
    }
  }

  /**
   * Redirects user to role-appropriate dashboard using backend-verified roles.
   *
   * ✅ OWNER → /owner/dashboard
   * ✅ USER → /pocetna
   * ✅ ADMIN → /admin (future-proofing)
   *
   * @param roles Backend-verified roles array from /api/users/me
   */
  private redirectToRoleBasedDashboard(roles: string[] | undefined): void {
    if (!roles || roles.length === 0) {
      console.warn('⚠️ No roles found - redirecting to /pocetna');
      void this.router.navigate(['/pocetna']);
      return;
    }

    // Priority: ADMIN > OWNER > USER
    if (roles.includes('ADMIN')) {
      console.log('👑 Redirecting ADMIN to /admin');
      void this.router.navigate(['/admin']); // Future-proofing
    } else if (roles.includes('OWNER')) {
      console.log('🚗 Redirecting OWNER to /owner/dashboard');
      void this.router.navigate(['/owner/dashboard']);
    } else if (roles.includes('USER')) {
      console.log('👤 Redirecting USER to /pocetna');
      void this.router.navigate(['/pocetna']);
    } else {
      console.warn('⚠️ Unknown role - redirecting to /pocetna');
      void this.router.navigate(['/pocetna']);
    }
  }
}
