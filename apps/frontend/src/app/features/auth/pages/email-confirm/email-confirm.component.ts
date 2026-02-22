import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpClient, HttpContext } from '@angular/common/http';

import { ToastService } from '@core/services/toast.service';
import { SKIP_AUTH } from '@core/auth/auth.tokens';
import { environment } from '@environments/environment';

import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';

/**
 * Component that handles Supabase email verification callback.
 *
 * Flow:
 * 1. User clicks email verification link from Supabase
 * 2. Supabase verifies the token
 * 3. Supabase redirects to /auth/confirm with access_token in URL hash
 * 4. This component extracts the token from URL hash
 * 5. Sends token to backend to enable user + set session cookies
 * 6. Redirects to dashboard
 *
 * URL format from Supabase:
 * /auth/confirm#access_token=xxx&refresh_token=xxx&type=signup
 */
@Component({
  selector: 'app-email-confirm',
  standalone: true,
  imports: [CommonModule, MatCardModule, MatProgressSpinnerModule, MatIconModule, MatButtonModule],
  template: `
    <div class="email-confirm-container">
      <mat-card>
        <mat-card-content>
          @if (isProcessing()) {
          <div class="processing">
            <mat-spinner diameter="48"></mat-spinner>
            <h2>Potvrđujemo Vašu email adresu...</h2>
            <p>Molimo sačekajte</p>
          </div>
          } @else if (errorMessage()) {
          <div class="error">
            <mat-icon color="warn">error</mat-icon>
            <h2>Greška pri potvrdi</h2>
            <p>{{ errorMessage() }}</p>
            <button mat-raised-button color="primary" (click)="goToLogin()">
              Nazad na prijavu
            </button>
          </div>
          } @else {
          <div class="success">
            <mat-icon color="primary">check_circle</mat-icon>
            <h2>Email potvrđen!</h2>
            <p>Vaš nalog je aktiviran. Preusmeravamo Vas...</p>
          </div>
          }
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [
    `
      .email-confirm-container {
        display: flex;
        justify-content: center;
        align-items: center;
        min-height: 80vh;
        padding: 1rem;
      }

      mat-card {
        max-width: 400px;
        width: 100%;
        text-align: center;
      }

      .processing,
      .error,
      .success {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 1rem;
        padding: 2rem;
      }

      mat-icon {
        font-size: 64px;
        width: 64px;
        height: 64px;
      }

      h2 {
        margin: 0;
      }

      p {
        color: rgba(0, 0, 0, 0.6);
        margin: 0;
      }
    `,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmailConfirmComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);

  protected readonly isProcessing = signal(true);
  protected readonly errorMessage = signal<string | null>(null);

  ngOnInit(): void {
    this.processEmailConfirmation();
  }

  /**
   * Extract tokens from URL hash and process email confirmation.
   *
   * Supabase redirects with format:
   * /auth/confirm#access_token=xxx&refresh_token=xxx&expires_in=3600&token_type=bearer&type=signup
   */
  private processEmailConfirmation(): void {
    // Get the fragment (hash) from URL
    const fragment = this.route.snapshot.fragment;

    if (!fragment) {
      // Check query params as fallback
      const errorParam = this.route.snapshot.queryParamMap.get('error');
      const errorDescription = this.route.snapshot.queryParamMap.get('error_description');

      if (errorParam) {
        this.handleError(errorDescription || errorParam);
        return;
      }

      this.handleError(
        'Nedostaju parametri za potvrdu emaila. Pokušajte ponovo sa linkom iz emaila.'
      );
      return;
    }

    // Parse the hash fragment
    const params = new URLSearchParams(fragment);
    const accessToken = params.get('access_token');
    const refreshToken = params.get('refresh_token');
    const type = params.get('type');
    const errorCode = params.get('error');
    const errorDesc = params.get('error_description');

    // Handle error in fragment
    if (errorCode) {
      this.handleError(errorDesc || errorCode);
      return;
    }

    // Validate we have tokens
    if (!accessToken) {
      this.handleError('Nedostaje access token. Link je možda istekao.');
      return;
    }

    console.log('📧 Email confirmation: type =', type, 'hasAccessToken =', !!accessToken);

    // Send tokens to backend to set session cookies and enable user
    this.confirmWithBackend(accessToken, refreshToken);
  }

  /**
   * Send Supabase tokens to backend to:
   * 1. Validate the tokens
   * 2. Enable the user account
   * 3. Set session cookies
   */
  private confirmWithBackend(accessToken: string, refreshToken: string | null): void {
    const context = new HttpContext().set(SKIP_AUTH, true);

    this.http
      .post<{ user: any; message: string }>(
        `${environment.baseApiUrl}/auth/supabase/confirm-email`,
        { accessToken, refreshToken },
        { context, withCredentials: true }
      )
      .subscribe({
        next: (response) => {
          console.log('✅ Email confirmation successful:', response.message);
          this.isProcessing.set(false);
          this.toast.success('Email potvrđen! Vaš nalog je aktiviran.');

          // Redirect to dashboard after short delay
          setTimeout(() => {
            this.router.navigate(['/']);
          }, 1500);
        },
        error: (err) => {
          console.error('❌ Email confirmation failed:', err);
          const message =
            err.error?.error ||
            err.error?.message ||
            'Potvrda emaila nije uspela. Pokušajte ponovo.';
          this.handleError(message);
        },
      });
  }

  private handleError(message: string): void {
    this.isProcessing.set(false);
    this.errorMessage.set(message);
    this.toast.error(message);
  }

  protected goToLogin(): void {
    this.router.navigate(['/auth/login']);
  }
}