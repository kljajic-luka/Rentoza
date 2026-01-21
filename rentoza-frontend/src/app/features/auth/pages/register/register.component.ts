import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ReactiveFormsModule, FormBuilder, Validators, FormGroup } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { finalize, tap } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import { RedirectService } from '@core/services/redirect.service';
import { ToastService } from '@core/services/toast.service';
import {
  RegisterRequest,
  UserRegisterRequest,
  OwnerRegisterRequest,
  OwnerType,
} from '@core/models/auth.model';
import { environment } from '@environments/environment';
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
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';

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
    MatCheckboxModule,
    MatRadioModule,
    MatDatepickerModule,
    MatNativeDateModule,
  ],
  templateUrl: './register.component.html',
  styleUrls: ['./register.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegisterComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly redirectService = inject(RedirectService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  // ═══════════════════════════════════════════════════════════════════════════
  // REACTIVE ROLE DETECTION - Responds to query parameter changes
  // ═══════════════════════════════════════════════════════════════════════════
  /**
   * Signal-based reactive owner registration state.
   * Automatically updates when URL query params change (e.g., navigating from
   * /auth/register?role=OWNER to /auth/register).
   */
  protected readonly isOwnerRegistration = signal(false);

  // ═══════════════════════════════════════════════════════════════════════════
  // PHASE 2: Enhanced Registration Feature Flag
  // ═══════════════════════════════════════════════════════════════════════════
  protected readonly isEnhancedRegistration = signal(
    (environment as { enhancedRegistration?: boolean }).enhancedRegistration ?? true
  );

  // Owner type selection (INDIVIDUAL or LEGAL_ENTITY)
  protected readonly ownerType = signal<OwnerType>('INDIVIDUAL');

  // Maximum date for DOB (21 years ago)
  protected readonly maxDateOfBirth = computed(() => {
    const date = new Date();
    date.setFullYear(date.getFullYear() - 21);
    return date;
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // FORM DEFINITION - Enhanced with Phase 2 fields
  // ═══════════════════════════════════════════════════════════════════════════

  readonly form = this.fb.nonNullable.group({
    firstName: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(50)]],
    lastName: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(50)]],
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
    // Phase 2: New required fields
    dateOfBirth: ['', [Validators.required, pastDateValidator(), minAgeValidator(21)]],
    confirmsAgeEligibility: [false, [Validators.requiredTrue]],
  });

  // Owner-specific form group (only used when isOwnerRegistration = true)
  readonly ownerForm = this.fb.group({
    ownerType: ['INDIVIDUAL' as OwnerType, [Validators.required]],
    jmbg: ['', []], // Validators set dynamically based on ownerType
    pib: ['', []],
    bankAccountNumber: ['', [ibanValidator()]],
    agreesToHostAgreement: [false, [Validators.requiredTrue]],
    confirmsVehicleInsurance: [false, [Validators.requiredTrue]],
    confirmsVehicleRegistration: [false, [Validators.requiredTrue]],
  });

  protected readonly isSubmitting = signal(false);

  /**
   * Initialize reactive query parameter subscription.
   * Uses takeUntilDestroyed for automatic cleanup.
   */
  ngOnInit(): void {
    // Subscribe to query parameter changes for reactive role switching
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const role = (params.get('role') || '').toUpperCase();
      const isOwner = role === 'OWNER';

      // Only update if the state actually changed
      if (this.isOwnerRegistration() !== isOwner) {
        this.isOwnerRegistration.set(isOwner);

        // Reset and reconfigure forms when switching modes
        this.resetFormsForMode(isOwner);
      }
    });

    // Set initial state from snapshot (for first load)
    const initialRole = (this.route.snapshot.queryParamMap.get('role') || '').toUpperCase();
    const isInitialOwner = initialRole === 'OWNER';
    this.isOwnerRegistration.set(isInitialOwner);

    // Initialize owner form validators if starting as owner
    if (isInitialOwner) {
      this.updateOwnerValidators('INDIVIDUAL');
    }
  }

  /**
   * Resets forms when switching between USER and OWNER registration modes.
   * Ensures clean state transition without stale data.
   */
  private resetFormsForMode(isOwner: boolean): void {
    if (isOwner) {
      // Switching TO owner mode: initialize owner form validators
      this.ownerType.set('INDIVIDUAL');
      this.ownerForm.reset({
        ownerType: 'INDIVIDUAL',
        jmbg: '',
        pib: '',
        bankAccountNumber: '',
        agreesToHostAgreement: false,
        confirmsVehicleInsurance: false,
        confirmsVehicleRegistration: false,
      });
      this.updateOwnerValidators('INDIVIDUAL');
    } else {
      // Switching TO user mode: clear owner form (optional, but keeps memory clean)
      this.ownerForm.reset();
    }
  }

  /**
   * Updates validators for JMBG/PIB/Bank fields based on selected owner type.
   * INDIVIDUAL: JMBG required, PIB cleared, Bank optional
   * LEGAL_ENTITY: PIB required, JMBG cleared, Bank required
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
      // Individual: JMBG required, PIB not needed, Bank optional
      jmbgControl?.setValidators([
        Validators.required,
        Validators.pattern(/^\d{13}$/),
        jmbgValidator(),
      ]);
      pibControl?.clearValidators();
      pibControl?.setValue('');
      bankControl?.clearValidators();
      bankControl?.setValidators([ibanValidator()]); // Optional but must be valid if provided
    } else {
      // Legal Entity: PIB required, JMBG not needed, Bank required
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

  submit(): void {
    // Validate main form
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    // Check password confirmation
    if (this.form.value.password !== this.form.value.confirmPassword) {
      this.form.markAllAsTouched();
      this.toast.error('Lozinke se ne podudaraju');
      return;
    }

    // For owner registration, also validate owner form
    if (this.isOwnerRegistration() && this.ownerForm.invalid) {
      this.ownerForm.markAllAsTouched();
      return;
    }

    this.isSubmitting.set(true);

    // Use enhanced registration if feature flag enabled
    if (this.isEnhancedRegistration()) {
      this.submitEnhancedRegistration();
    } else {
      this.submitLegacyRegistration();
    }
  }

  /**
   * Phase 2: Enhanced registration with DOB, age verification, and owner identity documents.
   */
  private submitEnhancedRegistration(): void {
    const { confirmPassword, ...basicData } = this.form.getRawValue();

    if (this.isOwnerRegistration()) {
      // Owner registration with identity documents
      const ownerData = this.ownerForm.getRawValue();
      const payload: OwnerRegisterRequest = {
        role: 'OWNER', // ✅ CRITICAL: Set role to OWNER for host registration
        firstName: basicData.firstName,
        lastName: basicData.lastName,
        email: basicData.email,
        phone: basicData.phone,
        password: basicData.password,
        dateOfBirth: basicData.dateOfBirth,
        confirmsAgeEligibility: basicData.confirmsAgeEligibility ?? false,
        ownerType: ownerData.ownerType as OwnerType,
        agreesToHostAgreement: ownerData.agreesToHostAgreement ?? false,
        confirmsVehicleInsurance: ownerData.confirmsVehicleInsurance ?? false,
        confirmsVehicleRegistration: ownerData.confirmsVehicleRegistration ?? false,
      };

      // Add conditional identity fields
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

      this.authService
        .supabaseRegister(payload as any)
        .pipe(
          tap((user) => {
            if (user === null) {
              // Email confirmation required
              this.toast.success(
                'Nalog je uspešno kreiran! Molimo proverite Vaš email za potvrdu naloga.',
                'Potvrda emaila potrebna'
              );
              this.router.navigate(['/auth/login'], {
                queryParams: { emailConfirmation: 'required' },
              });
            } else {
              this.toast.success(
                'Dobrodošli u Rentoza zajednicu domaćina! Vaš nalog je kreiran i čeka verifikaciju.'
              );
              // Owner redirects to verification pending or dashboard
              this.redirectService.redirectAfterLogin(user);
            }
          }),
          finalize(() => this.isSubmitting.set(false))
        )
        .subscribe({
          error: (err) => this.handleRegistrationError(err),
        });
    } else {
      // Standard user registration
      const payload: UserRegisterRequest = {
        role: 'USER', // ✅ CRITICAL: Set role to USER for standard registration
        firstName: basicData.firstName,
        lastName: basicData.lastName,
        email: basicData.email,
        phone: basicData.phone,
        password: basicData.password,
        dateOfBirth: basicData.dateOfBirth,
        confirmsAgeEligibility: basicData.confirmsAgeEligibility,
      };

      this.authService
        .supabaseRegister(payload)
        .pipe(
          tap((user) => {
            if (user === null) {
              // Email confirmation required - show success message but don't redirect
              this.toast.success(
                'Nalog je uspešno kreiran! Molimo proverite Vaš email za potvrdu naloga pre prijavljivanja.',
                'Potvrda emaila potrebna'
              );
              // Redirect to login page with message
              this.router.navigate(['/auth/login'], {
                queryParams: { emailConfirmation: 'required' },
              });
            } else {
              // Normal flow - user is logged in
              this.toast.success('Dobrodošli u Rentoza! Vaš nalog je uspešno kreiran.');
              this.redirectService.redirectAfterLogin(user!);
            }
          }),
          finalize(() => this.isSubmitting.set(false))
        )
        .subscribe({
          error: (err) => this.handleRegistrationError(err),
        });
    }
  }

  /**
   * Legacy registration (backward compatibility when feature flag disabled).
   */
  private submitLegacyRegistration(): void {
    const { confirmPassword, dateOfBirth, confirmsAgeEligibility, ...rest } =
      this.form.getRawValue();
    const payload: RegisterRequest = rest;

    if (this.isOwnerRegistration()) {
      payload.role = 'OWNER';
    }

    this.authService
      .supabaseRegister(payload as RegisterRequest)
      .pipe(
        tap((user) => {
          if (user === null) {
            // Email confirmation required
            this.toast.success(
              'Nalog je uspešno kreiran! Molimo proverite Vaš email za potvrdu naloga.',
              'Potvrda emaila potrebna'
            );
            this.router.navigate(['/auth/login'], {
              queryParams: { emailConfirmation: 'required' },
            });
          } else {
            this.toast.success(
              this.isOwnerRegistration()
                ? 'Dobrodošli u Rentoza zajednicu domaćina! Vaš nalog je kreiran.'
                : 'Dobrodošli u Rentoza! Vaš nalog je uspešno kreiran.'
            );
            this.redirectService.redirectAfterLogin(user);
          }
        }),
        finalize(() => this.isSubmitting.set(false))
      )
      .subscribe({
        error: () => {
          this.toast.error(
            'Registracija nije uspela. Molimo proverite podatke i pokušajte ponovo.'
          );
        },
      });
  }

  /**
   * Handles registration errors with specific messages for known error types.
   */
  private handleRegistrationError(err: { error?: { message?: string } }): void {
    const message = err.error?.message || '';

    if (message.includes('email') || message.includes('Email')) {
      this.toast.error('Ova email adresa je već registrovana.');
    } else if (message.includes('phone') || message.includes('Phone')) {
      this.toast.error('Ovaj broj telefona je već registrovan.');
    } else if (message.includes('JMBG') || message.includes('jmbg')) {
      this.toast.error('Ovaj JMBG je već registrovan.');
    } else if (message.includes('PIB') || message.includes('pib')) {
      this.toast.error('Ovaj PIB je već registrovan.');
    } else if (message.includes('age') || message.includes('21')) {
      this.toast.error('Morate imati najmanje 21 godinu za registraciju.');
    } else {
      this.toast.error('Registracija nije uspela. Molimo proverite podatke i pokušajte ponovo.');
    }
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
    if (this.isOwnerRegistration()) {
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
