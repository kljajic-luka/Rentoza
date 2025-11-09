import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { finalize, tap } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import { RedirectService } from '@core/services/redirect.service';
import { RegisterRequest } from '@core/models/auth.model';
import { environment } from '@environments/environment';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

@Component({
  selector: 'app-register-page',
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
    MatProgressSpinnerModule,
  ],
  templateUrl: './register.component.html',
  styleUrls: ['./register.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegisterComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly redirectService = inject(RedirectService);
  private readonly router = inject(Router);
  private readonly toastr = inject(ToastrService);
  private readonly route = inject(ActivatedRoute);
  private readonly requestedRole = (
    this.route.snapshot.queryParamMap.get('role') || ''
  ).toUpperCase();
  protected readonly isOwnerRegistration = this.requestedRole === 'OWNER';

  readonly form = this.fb.nonNullable.group({
    firstName: ['', [Validators.required]],
    lastName: ['', [Validators.required]],
    email: ['', [Validators.required, Validators.email]],
    phone: ['', [Validators.required, Validators.pattern(/^\d{8,15}$/)]],
    password: [
      '',
      [
        Validators.required,
        Validators.minLength(8),
        Validators.pattern(/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).+$/),
      ],
    ],
    confirmPassword: ['', [Validators.required]],
  });

  protected readonly isSubmitting = signal(false);

  submit(): void {
    if (this.form.invalid || this.form.value.password !== this.form.value.confirmPassword) {
      this.form.markAllAsTouched();
      return;
    }

    this.isSubmitting.set(true);
    const { confirmPassword, ...rest } = this.form.getRawValue();
    const payload: RegisterRequest = rest;

    if (this.isOwnerRegistration) {
      payload.role = 'OWNER';
    }

    this.authService
      .register(payload)
      .pipe(
        tap((user) => {
          this.toastr.success(
            this.isOwnerRegistration
              ? 'Nalog kreiran! Dobrodošli u Rentoza zajednicu domaćina.'
              : 'Nalog kreiran! Dobrodošli u Rentoza.',
            'Uspešna registracija'
          );
          // Redirect to role-specific dashboard
          this.redirectService.redirectAfterLogin(user);
        }),
        finalize(() => this.isSubmitting.set(false))
      )
      .subscribe({
        error: () => {
          this.toastr.error('Greška prilikom registracije. Pokušajte ponovo.');
        },
      });
  }

  /**
   * Initiate Google OAuth2 registration flow
   * Redirects user to backend OAuth2 authorization endpoint with role context
   *
   * ROLE-BASED REGISTRATION:
   * - For regular users: /oauth2/authorization/google (defaults to USER role)
   * - For owner registration: /oauth2/authorization/google?role=owner
   * - Backend's CustomAuthorizationRequestResolver captures the role parameter
   * - Role is embedded in OAuth2 state parameter for secure propagation
   * - After Google callback, user is provisioned with the correct role
   */
  registerWithGoogle(): void {
    // CRITICAL: OAuth2 endpoints are at root level (/oauth2/...), not under /api
    // environment.baseApiUrl = 'http://localhost:8080/api'
    // But OAuth2 endpoint is at: http://localhost:8080/oauth2/authorization/google
    // So we need to strip '/api' and use the base URL only
    const baseUrl = environment.baseApiUrl.replace('/api', '');
    let googleAuthUrl = `${baseUrl}/oauth2/authorization/google`;

    // CRITICAL: Pass role as query parameter for backend to capture
    // The backend expects ?role=owner to provision user as OWNER
    // Without this parameter, users default to USER role
    if (this.isOwnerRegistration) {
      googleAuthUrl += '?role=owner';
    }

    // Redirect to backend OAuth2 authorization endpoint
    // Backend will:
    // 1. Validate and embed role in OAuth2 state parameter
    // 2. Redirect to Google for authentication
    // 3. Handle callback and provision user with correct role
    // 4. Generate JWT with role claim
    // 5. Redirect to frontend with JWT token
    window.location.href = googleAuthUrl;
  }
}
