import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
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

  ngOnInit(): void {
    // DEBUG: Log the full URL to understand what we're receiving
    console.log('🔍 OAuth Callback - Full URL:', window.location.href);
    console.log('🔍 OAuth Callback - Hash:', window.location.hash);
    console.log('🔍 OAuth Callback - Search:', window.location.search);

    this.handleGoogleCallback();
  }

  private handleGoogleCallback(): void {
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
        console.log('🔑 Found access token in URL fragment (implicit flow)');
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

    if (!state) {
      console.error('Missing state parameter in callback');
      this.handleError('Nedostaje sigurnosni parametar. Pokušajte ponovo.');
      return;
    }

    // Process the callback
    this.processGoogleCallback(code, state);
  }

  /**
   * Handle implicit flow tokens from URL fragment.
   * Supabase returns access_token directly in the URL fragment.
   * We send this to our backend to verify and create/find the user.
   *
   * The role is extracted from the URL query params (passed via redirect_to URL).
   */
  private processImplicitFlowTokens(accessToken: string, refreshToken?: string): void {
    console.log('🔗 Processing Supabase implicit flow tokens');

    // Extract role from URL query parameters (Supabase preserves query params from redirect_to)
    // The URL format is: /auth/supabase/google/callback?role=USER#access_token=...
    const urlParams = new URLSearchParams(window.location.search);
    const role = urlParams.get('role') || 'USER';

    console.log('📋 Role from URL params:', role);

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

        console.log('✅ Implicit flow OAuth successful:', user.email);
        this.handleSuccessfulAuth(user);
      },
      error: (err) => {
        console.error('Implicit flow callback failed:', err);
        this.handleError(err.message || 'Prijavljivanje putem Google-a nije uspelo.');
      },
    });
  }

  private processGoogleCallback(code: string, state: string): void {
    console.log('🔗 Processing Google OAuth callback via Supabase');

    this.authService.handleSupabaseGoogleCallback(code, state).subscribe({
      next: (user) => {
        if (!user) {
          this.handleError('Neuspešna autentifikacija. Pokušajte ponovo.');
          return;
        }

        console.log('✅ Google OAuth successful:', user.email);
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
      console.log('📝 User has INCOMPLETE registration - redirecting to profile completion');

      const isOwner = user.roles?.includes('OWNER');
      const queryParams = isOwner ? { role: 'owner' } : {};

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
      console.log(`🔀 Redirecting to return URL: ${returnUrl}`);
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
      console.warn('⚠️ No roles found - redirecting to /pocetna');
      void this.router.navigate(['/pocetna']);
      return;
    }

    // Priority: ADMIN > OWNER > USER
    if (roles.includes('ADMIN')) {
      console.log('👑 Redirecting ADMIN to /admin');
      void this.router.navigate(['/admin']);
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
