import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import {
  ReactiveFormsModule,
  FormBuilder,
  Validators,
  AbstractControl,
  ValidationErrors,
} from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';

import { AuthService } from '@core/auth/auth.service';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { PasswordCheckPipe } from './password-check.pipe';

@Component({
  selector: 'app-reset-password-page',
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
    PasswordCheckPipe,
  ],
  templateUrl: './reset-password.component.html',
  styleUrls: ['./reset-password.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResetPasswordComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly isSubmitting = signal(false);
  readonly isSuccess = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly token = signal<string | null>(null);
  readonly tokenMissing = signal(false);

  readonly form = this.fb.nonNullable.group(
    {
      newPassword: [
        '',
        [Validators.required, Validators.minLength(8), this.passwordStrengthValidator],
      ],
      confirmPassword: ['', [Validators.required]],
    },
    { validators: this.passwordMatchValidator },
  );

  /** Password strength: 1 uppercase, 1 lowercase, 1 digit, 1 special char */
  passwordStrengthValidator(control: AbstractControl): ValidationErrors | null {
    const value = control.value;
    if (!value) return null;

    const errors: ValidationErrors = {};
    if (!/[A-Z]/.test(value)) errors['missingUppercase'] = true;
    if (!/[a-z]/.test(value)) errors['missingLowercase'] = true;
    if (!/[0-9]/.test(value)) errors['missingDigit'] = true;
    if (!/[^A-Za-z0-9]/.test(value)) errors['missingSpecial'] = true;

    return Object.keys(errors).length ? errors : null;
  }

  /** Confirm password must match new password */
  passwordMatchValidator(group: AbstractControl): ValidationErrors | null {
    const newPw = group.get('newPassword')?.value;
    const confirm = group.get('confirmPassword')?.value;
    if (newPw && confirm && newPw !== confirm) {
      return { passwordMismatch: true };
    }
    return null;
  }

  ngOnInit(): void {
    const tokenParam = this.route.snapshot.queryParamMap.get('token');
    if (!tokenParam) {
      this.tokenMissing.set(true);
    } else {
      this.token.set(tokenParam);
    }
  }

  submit(): void {
    if (this.form.invalid || this.isSubmitting() || !this.token()) return;

    this.isSubmitting.set(true);
    this.errorMessage.set(null);

    const { newPassword } = this.form.getRawValue();

    this.authService.resetPassword(this.token()!, newPassword).subscribe({
      next: () => {
        this.isSuccess.set(true);
        this.isSubmitting.set(false);
      },
      error: (err) => {
        const message = err?.error?.message || 'Resetovanje lozinke nije uspelo. Pokušajte ponovo.';
        this.errorMessage.set(message);
        this.isSubmitting.set(false);
      },
    });
  }

  navigateToLogin(): void {
    this.router.navigate(['/auth/login']);
  }
}
