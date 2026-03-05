import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  isDevMode,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { finalize, tap } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import { RedirectService } from '@core/services/redirect.service';
import { ToastService } from '@core/services/toast.service';
import { LoginRequest } from '@core/models/auth.model';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { FormInputComponent } from '@shared/components/form-input/form-input.component';
import { ButtonComponent } from '@shared/components/button/button.component';

type LoginFeedbackKind =
  | 'invalid_form'
  | 'invalid_credentials'
  | 'account_blocked'
  | 'account_locked'
  | 'rate_limited'
  | 'network'
  | 'generic';

interface LoginFeedback {
  kind: LoginFeedbackKind;
  title: string;
  message: string;
  icon: string;
  tone: 'error' | 'warning';
}

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
    MatProgressSpinnerModule,
    FormInputComponent,
    ButtonComponent,
  ],
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly redirectService = inject(RedirectService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);

  readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(6)]],
  });

  protected readonly isSubmitting = signal(false);
  protected readonly loginFeedback = signal<LoginFeedback | null>(null);

  constructor() {
    // Clear form-level feedback whenever the user edits any field
    this.form.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.resetLoginFeedback());
  }

  submit(): void {
    if (this.isSubmitting()) {
      return;
    }

    this.resetLoginFeedback();

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.loginFeedback.set({
        kind: 'invalid_form',
        title: 'Proverite unesene podatke',
        message: 'Unesite ispravnu email adresu i lozinku da biste nastavili.',
        icon: 'error_outline',
        tone: 'warning',
      });
      this.focusFirstInvalidControl();
      return;
    }

    this.isSubmitting.set(true);
    const payload: LoginRequest = this.form.getRawValue();

    this.authService
      .supabaseLogin(payload)
      .pipe(
        tap((user) => {
          this.toast.success('Dobrodošli nazad! Uspešno ste se prijavili.');

          // Check if there's a return URL, otherwise use role-based redirection
          const returnUrl = this.getReturnUrl();
          if (returnUrl) {
            void this.router.navigateByUrl(returnUrl);
          } else {
            this.redirectService.redirectAfterLogin(user);
          }
        }),
        finalize(() => this.isSubmitting.set(false)),
      )
      .subscribe({
        error: (error: unknown) => {
          const feedback = this.resolveLoginFeedback(error);
          this.loginFeedback.set(feedback);

          if (feedback.kind === 'invalid_credentials') {
            this.applyInvalidCredentialsState();
            this.form.controls.password.setValue('');
            this.focusControl('password');
          }

          if (isDevMode()) {
            console.error('Login failed:', error);
          }
        },
      });
  }

  onCredentialInput(): void {
    this.resetLoginFeedback();
  }

  /**
   * Initiate Google OAuth2 sign-in flow via Supabase.
   *
   * NEW IMPLEMENTATION: Uses Supabase Auth instead of direct Spring OAuth2.
   * Redirects user to Google via Supabase Auth.
   */
  signInWithGoogle(): void {
    // Preserve return URL if exists
    const returnUrl = this.getReturnUrl();
    if (returnUrl) {
      // Store return URL in session storage to retrieve after OAuth2 callback
      sessionStorage.setItem('oauth2_return_url', returnUrl);
    }

    // Use new Supabase Google OAuth flow
    this.authService.loginWithSupabaseGoogle('USER');
  }

  private getReturnUrl(): string | null {
    const qp = this.route.snapshot.queryParamMap;
    const raw = qp.get('returnUrl') || qp.get('redirectUrl');
    if (!raw) return null;

    // Allow only internal routes starting with "/" and disallow protocol-based redirects
    const trimmed = raw.trim();
    if (!trimmed.startsWith('/')) return null;
    if (trimmed.startsWith('//')) return null;
    if (/^https?:\/\//i.test(trimmed)) return null;

    return trimmed;
  }

  private resolveLoginFeedback(error: unknown): LoginFeedback {
    if (error instanceof HttpErrorResponse) {
      const apiMessage = this.extractApiMessage(error.error);
      const apiCode = this.extractApiCode(error.error);

      if (error.status === 423 && apiCode === 'ACCOUNT_LOCKED') {
        const retrySeconds = this.extractRetryAfterSeconds(error);
        const retryText = retrySeconds
          ? retrySeconds >= 60
            ? `Pokušajte ponovo za ${Math.ceil(retrySeconds / 60)} min.`
            : `Pokušajte ponovo za ${retrySeconds} sek.`
          : 'Pokušajte ponovo za nekoliko minuta.';
        return {
          kind: 'account_locked' as LoginFeedbackKind,
          title: 'Nalog je zaključan',
          message: `Previše neuspešnih pokušaja prijave. ${retryText}`,
          icon: 'lock',
          tone: 'warning',
        };
      }

      if (error.status === 403 && apiCode === 'ACCOUNT_BANNED') {
        return {
          kind: 'account_blocked',
          title: 'Nalog je blokiran',
          message: apiMessage || 'Vaš nalog je suspendovan. Obratite se podršci za pomoć.',
          icon: 'block',
          tone: 'error',
        };
      }

      if (error.status === 400 || error.status === 401) {
        return {
          kind: 'invalid_credentials',
          title: 'Prijava nije uspela',
          message: 'Email adresa ili lozinka nisu ispravni. Proverite unos i pokušajte ponovo.',
          icon: 'warning',
          tone: 'error',
        };
      }

      if (error.status === 429) {
        return {
          kind: 'rate_limited',
          title: 'Previše pokušaja prijave',
          message: 'Sačekajte nekoliko minuta pa pokušajte ponovo.',
          icon: 'schedule',
          tone: 'warning',
        };
      }

      if (error.status === 0) {
        return {
          kind: 'network',
          title: 'Nema konekcije',
          message:
            'Ne možemo da uspostavimo vezu sa serverom. Proverite internet i pokušajte ponovo.',
          icon: 'wifi_off',
          tone: 'error',
        };
      }
    }

    const message = error instanceof Error ? error.message.toLowerCase() : '';
    if (
      message.includes('invalid email') ||
      message.includes('invalid password') ||
      message.includes('email or password')
    ) {
      return {
        kind: 'invalid_credentials',
        title: 'Prijava nije uspela',
        message: 'Email adresa ili lozinka nisu ispravni. Proverite unos i pokušajte ponovo.',
        icon: 'warning',
        tone: 'error',
      };
    }

    if (
      message.includes('suspended') ||
      message.includes('blocked') ||
      message.includes('banned')
    ) {
      return {
        kind: 'account_blocked',
        title: 'Nalog je blokiran',
        message: 'Vaš nalog je suspendovan. Obratite se podršci za pomoć.',
        icon: 'block',
        tone: 'error',
      };
    }

    return {
      kind: 'generic',
      title: 'Prijava trenutno nije dostupna',
      message: 'Došlo je do greške na serveru. Pokušajte ponovo za nekoliko trenutaka.',
      icon: 'error',
      tone: 'error',
    };
  }

  private extractApiMessage(payload: unknown): string | null {
    if (!payload || typeof payload !== 'object') return null;
    const source = payload as Record<string, unknown>;

    const message = source['message'] ?? source['error_description'] ?? source['detail'];
    return typeof message === 'string' && message.trim() ? message.trim() : null;
  }

  private extractApiCode(payload: unknown): string | null {
    if (!payload || typeof payload !== 'object') return null;
    const source = payload as Record<string, unknown>;

    const code = source['error'] ?? source['code'];
    return typeof code === 'string' ? code.toUpperCase() : null;
  }

  private extractRetryAfterSeconds(error: HttpErrorResponse): number | null {
    // Prefer body field, fallback to header
    const bodySeconds = (error.error as Record<string, unknown>)?.['retryAfterSeconds'];
    if (typeof bodySeconds === 'number' && bodySeconds > 0) return bodySeconds;

    const header = error.headers?.get('Retry-After');
    if (header) {
      const parsed = parseInt(header, 10);
      if (!isNaN(parsed) && parsed > 0) return parsed;
    }
    return null;
  }

  private applyInvalidCredentialsState(): void {
    this.form.controls.email.setErrors({
      ...(this.form.controls.email.errors ?? {}),
      invalidCredentials: true,
    });
    this.form.controls.password.setErrors({
      ...(this.form.controls.password.errors ?? {}),
      invalidCredentials: true,
    });

    this.form.controls.email.markAsTouched();
    this.form.controls.password.markAsTouched();
  }

  private resetLoginFeedback(): void {
    this.loginFeedback.set(null);
    this.clearControlError('email', 'invalidCredentials');
    this.clearControlError('password', 'invalidCredentials');
  }

  private clearControlError(controlName: 'email' | 'password', errorKey: string): void {
    const control = this.form.controls[controlName];
    if (!control.hasError(errorKey)) return;

    const nextErrors = { ...(control.errors ?? {}) };
    delete nextErrors[errorKey];
    control.setErrors(Object.keys(nextErrors).length > 0 ? nextErrors : null);
  }

  private focusFirstInvalidControl(): void {
    if (this.form.controls.email.invalid) {
      this.focusControl('email');
      return;
    }

    if (this.form.controls.password.invalid) {
      this.focusControl('password');
    }
  }

  private focusControl(controlName: 'email' | 'password'): void {
    queueMicrotask(() => {
      const field = document.querySelector<HTMLInputElement>(
        `input[formControlName="${controlName}"]`,
      );
      field?.focus();
      field?.select();
    });
  }
}
