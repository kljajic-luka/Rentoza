import {
  Component,
  OnInit,
  OnDestroy,
  inject,
  signal,
  computed,
  ViewChild,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, ActivatedRoute } from '@angular/router';
import { FormBuilder, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { Subject, takeUntil } from 'rxjs';

import { RenterVerificationService } from '@core/services/renter-verification.service';
import {
  RenterVerificationProfile,
  DriverLicenseStatus,
  getStatusLabelSr,
  getStatusClass,
  getStatusIcon,
  canResubmit,
  isBlocked,
  shouldShowUploadForm,
  requiresAdditionalDocuments,
} from '@core/models/renter-verification.model';
import { LicensePhotoUploadComponent } from '../../components/license-photo-upload/license-photo-upload.component';
import { SelfieCaptureComponent } from '../../components/selfie-capture/selfie-capture.component';

/**
 * Renter Verification Page Component
 *
 * Main page for driver license verification flow.
 *
 * User Journey:
 * 1. User navigates to /verify-license
 * 2. Component loads current verification status
 * 3. Shows appropriate UI based on status:
 *    - NOT_STARTED: Upload form
 *    - PENDING_REVIEW: Polling status with progress
 *    - APPROVED: Success message with CTA
 *    - REJECTED: Rejection reason + re-upload form
 *    - EXPIRED: Expiry notice + re-upload form
 *    - SUSPENDED: Block message (no actions)
 *
 * Security:
 * - Files validated on client before upload
 * - Uses FormData (multipart/form-data) for upload
 * - No PII logged or stored locally
 * - Previews cleaned up on destroy
 */
@Component({
  selector: 'app-renter-verification-page',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatProgressBarModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatDatepickerModule,
    MatNativeDateModule,
    LicensePhotoUploadComponent,
    SelfieCaptureComponent,
  ],
  templateUrl: './renter-verification-page.component.html',
  styleUrls: ['./renter-verification-page.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RenterVerificationPageComponent implements OnInit, OnDestroy {
  // ============================================================================
  // INJECTIONS
  // ============================================================================

  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly verificationService = inject(RenterVerificationService);

  // ============================================================================
  // VIEW CHILDREN
  // ============================================================================

  @ViewChild('frontUpload') frontUpload!: LicensePhotoUploadComponent;
  @ViewChild('backUpload') backUpload!: LicensePhotoUploadComponent;

  // ============================================================================
  // STATE
  // ============================================================================

  /** Current verification status */
  readonly status = signal<RenterVerificationProfile | null>(null);

  /** Files selected for upload */
  readonly frontFile = signal<File | null>(null);
  readonly backFile = signal<File | null>(null);
  readonly selfieFile = signal<File | null>(null);

  /** Selfie consent given */
  readonly selfieConsentGiven = signal<boolean>(false);

  /** Upload state */
  readonly isSubmitting = signal<boolean>(false);
  readonly submitError = signal<string | null>(null);
  readonly submitSuccess = signal<boolean>(false);

  /** Return URL after verification */
  returnUrl: string | null = null;

  /** Optional expiry date form */
  expiryForm: FormGroup;

  /** Getter for expiry date control */
  get expiryDateControl(): FormControl {
    return this.expiryForm.get('expiryDate') as FormControl;
  }

  /** Minimum date for expiry (today) */
  readonly minExpiryDate = new Date();

  /** Destroy subject for cleanup */
  private readonly destroy$ = new Subject<void>();

  // ============================================================================
  // COMPUTED
  // ============================================================================

  /** Loading state from service */
  readonly loading = this.verificationService.loading;

  /** Error from service */
  readonly serviceError = this.verificationService.error;

  /** Upload progress from service */
  readonly uploadProgress = this.verificationService.uploadProgress;

  /** Is uploading from service */
  readonly isUploading = this.verificationService.isUploading;

  /** Current license status */
  readonly licenseStatus = computed(() => this.status()?.status ?? 'NOT_STARTED');

  /** Should show upload form - only when canResubmit (NOT_STARTED, REJECTED, EXPIRED) or incomplete docs during PENDING_REVIEW */
  readonly showUploadForm = computed(() => {
    const profile = this.status();
    const status = this.licenseStatus();
    const requiredComplete = profile?.requiredDocumentsComplete ?? false;
    return shouldShowUploadForm(status, requiredComplete);
  });

  /** Is pending with incomplete documents (needs to add more docs) */
  readonly isPendingIncomplete = computed(() => {
    const profile = this.status();
    const status = this.licenseStatus();
    const requiredComplete = profile?.requiredDocumentsComplete ?? false;
    return requiresAdditionalDocuments(status, requiredComplete);
  });

  /** Is pending with complete documents (waiting for admin review) */
  readonly isPendingComplete = computed(() => {
    const profile = this.status();
    return (
      this.licenseStatus() === 'PENDING_REVIEW' && (profile?.requiredDocumentsComplete ?? false)
    );
  });

  /** Is status terminal (approved, suspended) */
  readonly isTerminal = computed(() => {
    const s = this.licenseStatus();
    return s === 'APPROVED' || isBlocked(s);
  });

  /** Is pending review (polling active) */
  readonly isPending = computed(() => this.licenseStatus() === 'PENDING_REVIEW');

  /** Is approved */
  readonly isApproved = computed(() => this.licenseStatus() === 'APPROVED');

  /** Is blocked (suspended) */
  readonly isSuspended = computed(() => isBlocked(this.licenseStatus()));

  /** Can submit (front + back + selfie required) */
  readonly canSubmit = computed(() => {
    return (
      this.frontFile() !== null &&
      this.backFile() !== null &&
      this.selfieFile() !== null &&
      !this.isSubmitting() &&
      !this.isUploading()
    );
  });

  /** Has selfie been captured (optional but recommended) */
  readonly hasSelfie = computed(() => this.selfieFile() !== null);

  /** Status badge label */
  readonly statusLabel = computed(() => getStatusLabelSr(this.licenseStatus()));

  /** Status badge CSS class */
  readonly statusClass = computed(() => getStatusClass(this.licenseStatus()));

  /** Status icon */
  readonly statusIcon = computed(() => getStatusIcon(this.licenseStatus()));

  /** Blocked reason for display */
  readonly blockedReason = computed(
    () => this.status()?.bookingBlockedReason ?? this.status()?.rejectionReason ?? null,
  );

  /** Next step hint */
  readonly nextStep = computed(() => this.status()?.nextSteps ?? '');

  /** Estimated wait time */
  readonly estimatedWait = computed(() => this.status()?.estimatedWaitTime ?? '5-30 minuta');

  // ============================================================================
  // LIFECYCLE
  // ============================================================================

  constructor() {
    this.expiryForm = this.fb.group({
      expiryDate: [null],
    });
  }

  ngOnInit(): void {
    // Get return URL from query params
    this.returnUrl = this.route.snapshot.queryParams['returnUrl'] || '/cars';

    // Load verification status
    this.verificationService.loadStatus(true);

    // Subscribe to status changes
    this.verificationService.status$.pipe(takeUntil(this.destroy$)).subscribe((status) => {
      this.status.set(status);

      // Clear submit success when status changes to pending
      if (status?.status === 'PENDING_REVIEW') {
        this.submitSuccess.set(false);
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();

    // Stop polling if active
    this.verificationService.stopPolling();
  }

  // ============================================================================
  // EVENT HANDLERS
  // ============================================================================

  /**
   * Handle front license photo selection.
   */
  onFrontFileSelected(file: File): void {
    this.frontFile.set(file);
    this.submitError.set(null);
  }

  /**
   * Handle back license photo selection.
   */
  onBackFileSelected(file: File): void {
    this.backFile.set(file);
    this.submitError.set(null);
  }

  /**
   * Handle front file removal.
   */
  onFrontRemoved(): void {
    this.frontFile.set(null);
  }

  /**
   * Handle back file removal.
   */
  onBackRemoved(): void {
    this.backFile.set(null);
  }

  /**
   * Handle selfie capture.
   */
  onSelfieCaptured(file: File): void {
    this.selfieFile.set(file);
    this.submitError.set(null);
  }

  /**
   * Handle selfie removal.
   */
  onSelfieRemoved(): void {
    this.selfieFile.set(null);
  }

  /**
   * Handle selfie consent change.
   */
  onSelfieConsentChanged(consent: boolean): void {
    this.selfieConsentGiven.set(consent);
  }

  /**
   * Handle selfie capture error.
   */
  onSelfieError(error: string): void {
    // Log selfie capture error
    console.warn('Selfie capture error:', error);
    this.submitError.set('Greška pri snimanju selfija: ' + error);
  }

  /**
   * Submit license documents for verification.
   */
  onSubmit(): void {
    const front = this.frontFile();
    const back = this.backFile();
    const selfie = this.selfieFile();

    if (!front || !back) {
      this.submitError.set('Potrebne su obe strane vozačke dozvole');
      return;
    }

    if (!selfie) {
      this.submitError.set('Selfie fotografija je obavezna za verifikaciju identiteta');
      return;
    }

    this.isSubmitting.set(true);
    this.submitError.set(null);

    // Get optional expiry date
    const expiryDate = this.expiryForm.value.expiryDate
      ? this.formatDate(this.expiryForm.value.expiryDate)
      : undefined;

    this.verificationService
      .submitLicense(front, back, expiryDate, selfie ?? undefined)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.isSubmitting.set(false);
          this.submitSuccess.set(true);

          // Clear files
          this.frontFile.set(null);
          this.backFile.set(null);
          this.selfieFile.set(null);
          this.expiryForm.reset();
        },
        error: (err) => {
          this.isSubmitting.set(false);
          this.submitError.set(err.message || 'Otpremanje nije uspelo');
        },
      });
  }

  /**
   * Manual refresh of status.
   */
  onRefreshStatus(): void {
    this.verificationService.refreshStatus();
  }

  /**
   * Navigate to browse cars.
   */
  onBrowseCars(): void {
    this.router.navigate([this.returnUrl || '/cars']);
  }

  /**
   * Navigate back to profile.
   */
  onGoBack(): void {
    this.router.navigate(['/users/profile']);
  }

  // ============================================================================
  // HELPERS
  // ============================================================================

  /**
   * Format date to ISO string (YYYY-MM-DD).
   */
  private formatDate(date: Date): string {
    return date.toISOString().split('T')[0];
  }
}
