import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { finalize, tap } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import { RedirectService } from '@core/services/redirect.service';
import { LoginRequest } from '@core/models/auth.model';
import { environment } from '@environments/environment';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

@Component({
  selector: 'app-login-page',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule
  ],
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly redirectService = inject(RedirectService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toastr = inject(ToastrService);

  readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(6)]]
  });

  protected readonly isSubmitting = signal(false);

  submit(): void {
    if (this.form.invalid || this.isSubmitting()) {
      this.form.markAllAsTouched();
      return;
    }

    this.isSubmitting.set(true);
    const payload: LoginRequest = this.form.getRawValue();

    this.authService
      .login(payload)
      .pipe(
        tap((user) => {
          this.toastr.success('Uspešno ste se prijavili!');

          // Check if there's a return URL, otherwise use role-based redirection
          const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
          if (returnUrl) {
            void this.router.navigateByUrl(returnUrl);
          } else {
            this.redirectService.redirectAfterLogin(user);
          }
        }),
        finalize(() => this.isSubmitting.set(false))
      )
      .subscribe({
        error: () => {
          this.toastr.error('Pogrešan email ili lozinka');
        }
      });
  }

  /**
   * Initiate Google OAuth2 sign-in flow
   * Redirects user to backend OAuth2 authorization endpoint
   */
  signInWithGoogle(): void {
    // CRITICAL: OAuth2 endpoints are at root level (/oauth2/...), not under /api
    // environment.baseApiUrl = 'http://localhost:8080/api'
    // But OAuth2 endpoint is at: http://localhost:8080/oauth2/authorization/google
    const baseUrl = environment.baseApiUrl.replace('/api', '');
    const googleAuthUrl = `${baseUrl}/oauth2/authorization/google`;

    // Preserve return URL if exists
    const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
    if (returnUrl) {
      // Store return URL in session storage to retrieve after OAuth2 callback
      sessionStorage.setItem('oauth2_return_url', returnUrl);
    }

    // Redirect to backend OAuth2 endpoint
    // Backend will redirect to Google, then back to /auth/callback with token
    window.location.href = googleAuthUrl;
  }
}
