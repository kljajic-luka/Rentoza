import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { finalize } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import { RedirectService } from '@core/services/redirect.service';
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
  imports: [
    CommonModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatIconModule
  ],
  templateUrl: './auth-callback.component.html',
  styleUrls: ['./auth-callback.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AuthCallbackComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly authService = inject(AuthService);
  private readonly redirectService = inject(RedirectService);
  private readonly toastr = inject(ToastrService);

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
          queryParams: { error: 'google_auth_failed' }
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
          queryParams: { error: 'no_token' }
        });
      }, 2000);
      return;
    }

    // Process successful OAuth2 authentication
    this.processGoogleToken(token);
  }

  private processGoogleToken(token: string): void {
    // Inject token into AuthService (same as local login)
    this.authService.setAccessToken(token);

    // Fetch user profile to populate auth state
    this.authService
      .refreshUserProfile()
      .pipe(finalize(() => this.isProcessing.set(false)))
      .subscribe({
        next: (user) => {
          this.toastr.success('Uspešno ste se prijavili putem Google naloga!');

          // Check if there's a return URL, otherwise use role-based redirection
          const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
          if (returnUrl) {
            void this.router.navigateByUrl(returnUrl);
          } else {
            this.redirectService.redirectAfterLogin(user);
          }
        },
        error: (err) => {
          console.error('Failed to fetch user profile after OAuth2 login:', err);
          this.errorMessage.set('Neuspešno učitavanje korisničkog profila.');

          this.toastr.error('Neuspešno učitavanje profila', 'Greška');

          // Clear the session and redirect to login
          this.authService.clearSession();

          setTimeout(() => {
            void this.router.navigate(['/auth/login'], {
              queryParams: { error: 'profile_load_failed' }
            });
          }, 2000);
        }
      });
  }
}
