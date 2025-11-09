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
   * Redirects user to backend OAuth2 registration endpoint
   */
  registerWithGoogle(): void {
    // Construct the Google OAuth2 registration URL
    // This endpoint sets REGISTER mode in session before redirecting to OAuth2
    const googleRegisterUrl = `${environment.baseApiUrl}/auth/google/register`;

    // Preserve role if registering as owner
    if (this.isOwnerRegistration) {
      // Store role in session storage to apply after OAuth2 callback
      sessionStorage.setItem('oauth2_register_role', 'OWNER');
    }

    // Redirect to backend Google registration endpoint
    // Backend will set mode=REGISTER in session, then redirect to Google
    window.location.href = googleRegisterUrl;
  }
}
