import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { finalize, tap } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import { RedirectService } from '@core/services/redirect.service';
import { ToastService } from '@core/services/toast.service';
import {
  GoogleOAuthCompletionRequest,
  OwnerType,
  EnhancedUserProfile,
} from '@core/models/auth.model';
import {
  minAgeValidator,
  pastDateValidator,
  jmbgValidator,
  pibValidator,
  ibanValidator,
} from '@shared/validators/identity-document.validators';

import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatRadioModule } from '@angular/material/radio';
import { MatStepperModule } from '@angular/material/stepper';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { STEPPER_GLOBAL_OPTIONS } from '@angular/cdk/stepper';

/**
 * OAuth Profile Completion Component
 *
 * This component handles the completion of Google OAuth user profiles.
 * Google OAuth provides limited user data (email, name) but not phone/DOB.
 * This 3-step wizard collects the missing required information.
 *
 * Flow:
 * 1. Google OAuth → Backend creates user with registrationStatus=INCOMPLETE
 * 2. auth-callback detects INCOMPLETE → redirects to /auth/complete-profile
 * 3. User completes this wizard → profile updated → redirected to dashboard
 *
 * @see REGISTRATION_IMPLEMENTATION_PLAN.md lines 672-917
 */
@Component({
  selector: 'app-oauth-complete',
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
    MatCheckboxModule,
    MatRadioModule,
    MatStepperModule,
    MatDatepickerModule,
    MatNativeDateModule,
  ],
  templateUrl: './oauth-complete.component.html',
  styleUrls: ['./oauth-complete.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: STEPPER_GLOBAL_OPTIONS,
      useValue: { showError: true },
    },
  ],
})
export class OAuthCompleteComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly redirectService = inject(RedirectService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(ToastService);

  // Determine if this is owner registration from query param
  private readonly requestedRole = (
    this.route.snapshot.queryParamMap.get('role') || ''
  ).toUpperCase();
  protected readonly isOwnerRegistration = this.requestedRole === 'OWNER';

  // Current user profile (loaded on init)
  protected readonly currentUser = signal<EnhancedUserProfile | null>(null);

  // Whether lastName needs to be collected (if it's "GooglePlaceholder")
  protected readonly needsLastName = computed(() => {
    const user = this.currentUser();
    return user?.lastName === 'GooglePlaceholder' || !user?.lastName;
  });

  // Owner type selection
  protected readonly ownerType = signal<OwnerType>('INDIVIDUAL');

  // Maximum date for DOB (21 years ago)
  protected readonly maxDateOfBirth = computed(() => {
    const date = new Date();
    date.setFullYear(date.getFullYear() - 21);
    return date;
  });

  // Loading states
  protected readonly isLoading = signal(true);
  protected readonly isSubmitting = signal(false);

  // ═══════════════════════════════════════════════════════════════════════════
  // STEP 1: Basic Information Form
  // ═══════════════════════════════════════════════════════════════════════════

  readonly basicForm = this.fb.nonNullable.group({
    lastName: ['', []], // Validators set conditionally based on needsLastName
    phone: ['', [Validators.required, Validators.pattern(/^\d{8,15}$/)]],
    dateOfBirth: ['', [Validators.required, pastDateValidator(), minAgeValidator(21)]],
    confirmsAgeEligibility: [false, [Validators.requiredTrue]],
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // STEP 2: Owner Information Form (only for owner registration)
  // ═══════════════════════════════════════════════════════════════════════════

  readonly ownerForm = this.fb.group({
    ownerType: ['INDIVIDUAL' as OwnerType, [Validators.required]],
    jmbg: ['', []], // Validators set dynamically
    pib: ['', []],
    bankAccountNumber: ['', [ibanValidator()]],
    agreesToHostAgreement: [false, [Validators.requiredTrue]],
    confirmsVehicleInsurance: [false, [Validators.requiredTrue]],
    confirmsVehicleRegistration: [false, [Validators.requiredTrue]],
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // STEP 3: Confirmation Form
  // ═══════════════════════════════════════════════════════════════════════════

  readonly confirmForm = this.fb.nonNullable.group({
    confirmsDataCorrect: [false, [Validators.requiredTrue]],
  });

  ngOnInit(): void {
    this.loadCurrentUser();
    if (this.isOwnerRegistration) {
      this.updateOwnerValidators('INDIVIDUAL');
    }
  }

  /**
   * Loads current user profile to check for placeholder values.
   */
  private async loadCurrentUser(): Promise<void> {
    try {
      const user = await this.authService.verifySession();
      if (!user) {
        this.toast.error('Sesija je istekla. Molimo prijavite se ponovo.');
        this.router.navigate(['/auth/login']);
        return;
      }

      this.currentUser.set(user as EnhancedUserProfile);

      // If lastName is placeholder, make it required
      if (this.needsLastName()) {
        this.basicForm
          .get('lastName')
          ?.setValidators([Validators.required, Validators.minLength(3), Validators.maxLength(50)]);
        this.basicForm.get('lastName')?.updateValueAndValidity();
      }

      this.isLoading.set(false);
    } catch (error) {
      console.error('Failed to load user profile:', error);
      this.toast.error('Greška pri učitavanju profila.');
      this.router.navigate(['/auth/login']);
    }
  }

  /**
   * Updates validators for JMBG/PIB/Bank fields based on owner type.
   */
  onOwnerTypeChange(ownerType: OwnerType): void {
    this.ownerType.set(ownerType);
    this.updateOwnerValidators(ownerType);
  }

  private updateOwnerValidators(ownerType: OwnerType): void {
    const jmbgControl = this.ownerForm.get('jmbg');
    const pibControl = this.ownerForm.get('pib');
    const bankControl = this.ownerForm.get('bankAccountNumber');

    if (ownerType === 'INDIVIDUAL') {
      jmbgControl?.setValidators([
        Validators.required,
        Validators.pattern(/^\d{13}$/),
        jmbgValidator(),
      ]);
      pibControl?.clearValidators();
      pibControl?.setValue('');
      bankControl?.clearValidators();
      bankControl?.setValidators([ibanValidator()]);
    } else {
      pibControl?.setValidators([
        Validators.required,
        Validators.pattern(/^\d{9}$/),
        pibValidator(),
      ]);
      jmbgControl?.clearValidators();
      jmbgControl?.setValue('');
      bankControl?.setValidators([Validators.required, ibanValidator()]);
    }

    jmbgControl?.updateValueAndValidity();
    pibControl?.updateValueAndValidity();
    bankControl?.updateValueAndValidity();
  }

  /**
   * Submits the completed profile to backend.
   */
  submit(): void {
    // Validate all forms
    if (this.basicForm.invalid) {
      this.basicForm.markAllAsTouched();
      return;
    }

    if (this.isOwnerRegistration && this.ownerForm.invalid) {
      this.ownerForm.markAllAsTouched();
      return;
    }

    if (this.confirmForm.invalid) {
      this.confirmForm.markAllAsTouched();
      return;
    }

    this.isSubmitting.set(true);

    const basicData = this.basicForm.getRawValue();
    const payload: GoogleOAuthCompletionRequest = {
      phone: basicData.phone,
      dateOfBirth: basicData.dateOfBirth,
      confirmsAgeEligibility: basicData.confirmsAgeEligibility,
    };

    // Include lastName if it was required (placeholder)
    if (this.needsLastName() && basicData.lastName) {
      payload.lastName = basicData.lastName;
    }

    // Include owner fields if owner registration
    if (this.isOwnerRegistration) {
      const ownerData = this.ownerForm.getRawValue();
      payload.ownerType = ownerData.ownerType as OwnerType;
      payload.agreesToHostAgreement = ownerData.agreesToHostAgreement ?? undefined;
      payload.confirmsVehicleInsurance = ownerData.confirmsVehicleInsurance ?? undefined;
      payload.confirmsVehicleRegistration = ownerData.confirmsVehicleRegistration ?? undefined;

      if (ownerData.ownerType === 'INDIVIDUAL' && ownerData.jmbg) {
        payload.jmbg = ownerData.jmbg;
      }
      if (ownerData.ownerType === 'LEGAL_ENTITY') {
        if (ownerData.pib) payload.pib = ownerData.pib;
        if (ownerData.bankAccountNumber) payload.bankAccountNumber = ownerData.bankAccountNumber;
      }
      if (ownerData.bankAccountNumber && ownerData.ownerType === 'INDIVIDUAL') {
        payload.bankAccountNumber = ownerData.bankAccountNumber;
      }
    }

    this.authService
      .completeOAuthProfile(payload)
      .pipe(
        tap((user) => {
          this.toast.success(
            this.isOwnerRegistration
              ? 'Profil je kompletiran! Vaš nalog čeka verifikaciju.'
              : 'Profil je uspešno kompletiran! Dobrodošli u Rentoza.'
          );
          this.redirectService.redirectAfterLogin(user);
        }),
        finalize(() => this.isSubmitting.set(false))
      )
      .subscribe({
        error: (err) => this.handleCompletionError(err),
      });
  }

  /**
   * Handles profile completion errors with specific messages.
   */
  private handleCompletionError(err: { error?: { message?: string } }): void {
    const message = err.error?.message || '';

    if (message.includes('phone') || message.includes('Phone')) {
      this.toast.error('Ovaj broj telefona je već registrovan.');
    } else if (message.includes('JMBG') || message.includes('jmbg')) {
      this.toast.error('Ovaj JMBG je već registrovan.');
    } else if (message.includes('PIB') || message.includes('pib')) {
      this.toast.error('Ovaj PIB je već registrovan.');
    } else if (message.includes('age') || message.includes('21')) {
      this.toast.error('Morate imati najmanje 21 godinu.');
    } else {
      this.toast.error('Greška pri kompletiranju profila. Pokušajte ponovo.');
    }
  }

  /**
   * Gets the summary data for the review step.
   */
  protected getSummaryData() {
    const basic = this.basicForm.getRawValue();
    const owner = this.isOwnerRegistration ? this.ownerForm.getRawValue() : null;
    const user = this.currentUser();

    return {
      firstName: user?.firstName || '',
      lastName: this.needsLastName() ? basic.lastName : user?.lastName || '',
      email: user?.email || '',
      phone: basic.phone,
      dateOfBirth: basic.dateOfBirth,
      ownerType: owner?.ownerType,
      hasJmbg: !!owner?.jmbg,
      hasPib: !!owner?.pib,
      hasBankAccount: !!owner?.bankAccountNumber,
    };
  }
}
