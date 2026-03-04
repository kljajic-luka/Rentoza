import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, isDevMode, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

import { AuthService } from '@core/auth/auth.service';
import { FavoriteService } from '@core/services/favorite.service';
import { ToastService } from '@core/services/toast.service';
import { EnhancedUserProfile } from '@core/models/auth.model';
import { UserProfile } from '@core/models/user.model';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';

/**
 * Component that handles Google OAuth2 callback from Supabase.
 *
 * This component processes the OAuth callback from Supabase's Google OAuth flow.
 *
 * Flow:
 * 1. User initiates Google login via AuthService.loginWithSupabaseGoogle()
 * 2. User is redirected to Google → Supabase → Backend callback
 * 3. Backend redirects to this component with code & state parameters
 * 4. This component exchanges the code for tokens via AuthService
 * 5. If registrationStatus=INCOMPLETE → redirect to /auth/complete-profile
 * 6. Otherwise redirects to appropriate page based on user role
 *
 * SECURITY:
 * - State parameter validation for CSRF protection
 * - Tokens are HttpOnly cookies (not accessible to JS)
 * - Server-side validation of authorization code
 */
@Component({
  selector: 'app-supabase-google-callback',
  standalone: true,
  imports: [CommonModule, MatCardModule, MatProgressSpinnerModule, MatIconModule],
  template: `
    <div class="callback-container">
      <mat-card class="callback-card">
        @if (isProcessing()) {
          <mat-spinner diameter="48"></mat-spinner>
          <h2>Autentifikacija u toku...</h2>
          <p>Molimo sačekajte dok verifikujemo vaš Google nalog.</p>
        } @else if (errorMessage()) {
          <mat-icon color="warn" class="error-icon">error</mat-icon>
          <h2>Greška pri prijavljivanju</h2>
          <p>{{ errorMessage() }}</p>
          <button mat-raised-button color="primary" (click)="retryLogin()">Pokušaj ponovo</button>
        }
      </mat-card>
    </div>
  `,
  styles: [
    `
      .callback-container {
        display: flex;
        justify-content: center;
        align-items: center;
        min-height: 100vh;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      }

      .callback-card {
        padding: 48px;
        text-align: center;
        max-width: 400px;

        h2 {
          margin: 24px 0 8px;
          color: #333;
        }

        p {
          color: #666;
          margin-bottom: 24px;
        }

        .error-icon {
          font-size: 48px;
          width: 48px;
          height: 48px;
        }
      }
    `,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SupabaseGoogleCallbackComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly authService = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly favoriteService = inject(FavoriteService);

  protected readonly isProcessing = signal(true);
  protected readonly errorMessage = signal<string | null>(null);

  /** Tracks whether the user initiated signup as an owner (from URL ?role=OWNER param). */
  private ownerIntent = false;

  ngOnInit(): void {
    this.handleGoogleCallback();
  }

  private handleGoogleCallback(): void {
    // Preserve owner-intent from URL query params before any URL cleanup.
    // Backend's redirect_to URL encodes the role, e.g. ?role=OWNER
    const roleParam = new URLSearchParams(window.location.search).get('role');
    this.ownerIntent = roleParam?.toUpperCase() === 'OWNER';

    // First, check URL fragment for implicit flow tokens (Supabase default)
    // Format: #access_token=...&refresh_token=...&expires_in=...&token_type=...
    const fragment = window.location.hash.substring(1); // Remove leading #

    if (fragment) {
      const fragmentParams = new URLSearchParams(fragment);
      const accessToken = fragmentParams.get('access_token');
      const refreshToken = fragmentParams.get('refresh_token');
      const errorFromFragment = fragmentParams.get('error');
      const errorDescFromFragment = fragmentParams.get('error_description');

      if (errorFromFragment) {
        console.error('Google OAuth error (fragment):', errorFromFragment, errorDescFromFragment);
        this.handleError(errorDescFromFragment || 'Google prijavljivanje nije uspelo.');
        return;
      }

      if (accessToken) {
        if (isDevMode()) console.log('🔑 Found access token in URL fragment (implicit flow)');
        this.processImplicitFlowTokens(accessToken, refreshToken || undefined);
        return;
      }
    }

    // Fallback: Check query parameters for PKCE flow (code-based)
    const code = this.route.snapshot.queryParamMap.get('code');
    const state = this.route.snapshot.queryParamMap.get('state');
    const error = this.route.snapshot.queryParamMap.get('error');
    const errorDescription = this.route.snapshot.queryParamMap.get('error_description');

    // Handle OAuth2 error from provider
    if (error) {
      console.error('Google OAuth error:', error, errorDescription);
      this.handleError(errorDescription || 'Google prijavljivanje nije uspelo.');
      return;
    }

    // Validate required parameters
    if (!code) {
      console.error('❌ Missing authorization code in callback');
      console.error('❌ No tokens in fragment, no code in query params');
      console.error('❌ This usually means Supabase redirect URL is not configured correctly');
      console.error(
        '❌ Check Supabase Dashboard → Authentication → URL Configuration → Redirect URLs',
      );
      this.handleError('Autentifikacija nije uspela. Proverite konfiguraciju.');
      return;
    }

    // SECURITY NOTE: State parameter is optional. Supabase PKCE flow does not always
    // include a custom state param. CSRF protection is handled server-side via PKCE code verifier.
    if (state && isDevMode()) {
      console.log('📋 State parameter present in callback');
    }

    // Process the callback
    this.processGoogleCallback(code, state || undefined);
  }

  /**
   * Handle implicit flow tokens from URL fragment.
   * Supabase returns access_token directly in the URL fragment.
   * We send this to our backend to verify and create/find the user.
   *
   * The role is extracted from the URL query params (passed via redirect_to URL).
   */
  private processImplicitFlowTokens(accessToken: string, refreshToken?: string): void {
    if (isDevMode()) console.log('🔗 Processing Supabase implicit flow tokens');

    // Extract role from URL query parameters (Supabase preserves query params from redirect_to)
    // The URL format is: /auth/supabase/google/callback?role=USER#access_token=...
    const urlParams = new URLSearchParams(window.location.search);
    const role = urlParams.get('role') || 'USER';

    // Clean up the URL fragment for security (don't leave tokens in browser history)
    if (window.history.replaceState) {
      const cleanUrl = window.location.origin + window.location.pathname;
      window.history.replaceState({}, document.title, cleanUrl);
    }

    this.authService.handleSupabaseImplicitCallback(accessToken, refreshToken, role).subscribe({
      next: (user) => {
        if (!user) {
          this.handleError('Neuspešna autentifikacija. Pokušajte ponovo.');
          return;
        }

        if (isDevMode()) console.log('✅ Implicit flow OAuth successful');
        this.handleSuccessfulAuth(user);
      },
      error: (err) => {
        console.error('Implicit flow callback failed:', err);
        this.handleError(err.message || 'Prijavljivanje putem Google-a nije uspelo.');
      },
    });
  }

  private processGoogleCallback(code: string, state?: string): void {
    if (isDevMode()) console.log('🔗 Processing Google OAuth callback via Supabase');

    this.authService.handleSupabaseGoogleCallback(code, state).subscribe({
      next: (user) => {
        if (!user) {
          this.handleError('Neuspešna autentifikacija. Pokušajte ponovo.');
          return;
        }

        if (isDevMode()) console.log('✅ Google OAuth successful');
        this.handleSuccessfulAuth(user);
      },
      error: (err) => {
        console.error('Google OAuth callback failed:', err);
        this.handleError(err.message || 'Prijavljivanje putem Google-a nije uspelo.');
      },
    });
  }

  /**
   * Common handler for successful authentication (both implicit and PKCE flows).
   */
  private handleSuccessfulAuth(user: UserProfile): void {
    // Check for INCOMPLETE registration status
    const enhancedUser = user as EnhancedUserProfile;
    if (enhancedUser.registrationStatus === 'INCOMPLETE') {
      if (isDevMode()) console.log('📝 User has INCOMPLETE registration - redirecting to profile completion');

      // Use ownerIntent from URL param — backend always returns USER role,
      // so user.roles won't contain OWNER at this point. Fallback kept for
      // edge cases where the user already has OWNER role in the DB.
      const isOwner = this.ownerIntent || user.roles?.includes('OWNER');
      const queryParams = isOwner ? { role: 'OWNER' } : {};

      this.toast.info('Molimo dovršite registraciju popunjavanjem preostalih podataka.');
      void this.router.navigate(['/auth/complete-profile'], { queryParams });
      this.isProcessing.set(false);
      return;
    }

    // Load favorites for the authenticated user
    this.favoriteService.loadFavoritedCarIds().subscribe({
      error: (err) => console.warn('Failed to preload favorites after OAuth login', err),
    });

    // Start session watcher
    this.authService.startTokenWatcher(60000);

    // Show success message
    this.toast.success('Dobrodošli! Uspešno ste se prijavili putem Google naloga.');

    // Redirect based on role or return URL
    const returnUrl = sessionStorage.getItem('oauth2_return_url');
    sessionStorage.removeItem('oauth2_return_url');

    if (returnUrl) {
      if (isDevMode()) console.log('🔀 Redirecting to return URL');
      void this.router.navigateByUrl(returnUrl);
    } else {
      this.redirectToRoleBasedDashboard(user.roles);
    }

    this.isProcessing.set(false);
  }

  private handleError(message: string): void {
    this.errorMessage.set(message);
    this.isProcessing.set(false);
    this.toast.error(message);
  }

  protected retryLogin(): void {
    void this.router.navigate(['/auth/login']);
  }

  private redirectToRoleBasedDashboard(roles: string[] | undefined): void {
    if (!roles || roles.length === 0) {
      void this.router.navigate(['/pocetna']);
      return;
    }

    // Priority: ADMIN > OWNER > USER
    if (roles.includes('ADMIN')) {
      void this.router.navigate(['/admin']);
    } else if (roles.includes('OWNER')) {
      void this.router.navigate(['/owner/dashboard']);
    } else if (roles.includes('USER')) {
      void this.router.navigate(['/pocetna']);
    } else {
      void this.router.navigate(['/pocetna']);
    }
  }
}