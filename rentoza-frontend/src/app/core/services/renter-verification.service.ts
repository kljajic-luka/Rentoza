import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient, HttpEventType, HttpParams } from '@angular/common/http';
import { Observable, BehaviorSubject, Subject, throwError, of, timer, EMPTY } from 'rxjs';
import {
  shareReplay,
  tap,
  catchError,
  switchMap,
  takeUntil,
  filter,
  map,
  finalize,
  retry,
  distinctUntilChanged,
} from 'rxjs/operators';

import {
  RenterVerificationProfile,
  BookingEligibility,
  DriverLicenseStatus,
  isApproved,
  isTerminal,
} from '@core/models/renter-verification.model';

/**
 * Service for managing renter driver license verification.
 *
 * Features:
 * - Verification status caching with shareReplay
 * - File upload with progress tracking
 * - Status polling during PENDING_REVIEW with exponential backoff
 * - Eligibility checks for booking flow gate
 * - PII-safe design (no logging of sensitive data)
 *
 * @example
 * ```typescript
 * // In component
 * private verification = inject(RenterVerificationService);
 *
 * ngOnInit() {
 *   this.verification.loadStatus();
 *   this.status$ = this.verification.status$;
 * }
 *
 * onSubmitLicense(front: File, back: File) {
 *   this.verification.submitLicense(front, back).subscribe();
 * }
 * ```
 */
@Injectable({ providedIn: 'root' })
export class RenterVerificationService {
  private readonly http = inject(HttpClient);

  private readonly API_BASE = '/api/users';

  // ============================================================================
  // STATE MANAGEMENT
  // ============================================================================

  /** Current verification status (cached) */
  private readonly statusSubject = new BehaviorSubject<RenterVerificationProfile | null>(null);

  /** Observable stream of verification status */
  readonly status$ = this.statusSubject.asObservable();

  /** Loading state signal */
  readonly loading = signal<boolean>(false);

  /** Error state signal */
  readonly error = signal<string | null>(null);

  /** Upload progress signal (0-100) */
  readonly uploadProgress = signal<number>(0);

  /** Is currently uploading signal */
  readonly isUploading = signal<boolean>(false);

  // ============================================================================
  // POLLING STATE
  // ============================================================================

  /** Subject to stop polling */
  private readonly stopPolling$ = new Subject<void>();

  /** Is polling active */
  private isPolling = false;

  /** Current poll attempt (for exponential backoff) */
  private pollAttempt = 0;

  /** Maximum poll attempts before stopping */
  private readonly MAX_POLL_ATTEMPTS = 20; // ~10 minutes with backoff

  /** Base polling interval in ms */
  private readonly BASE_POLL_INTERVAL = 5000; // 5 seconds

  /** Max polling interval in ms */
  private readonly MAX_POLL_INTERVAL = 30000; // 30 seconds

  // ============================================================================
  // COMPUTED SIGNALS
  // ============================================================================

  /** Current status value */
  readonly currentStatus = computed(() => this.statusSubject.getValue());

  /** Is user approved to book */
  readonly isApproved = computed(() => {
    const status = this.statusSubject.getValue();
    return status ? isApproved(status.status) : false;
  });

  /** Can user currently book cars */
  readonly canBook = computed(() => {
    const status = this.statusSubject.getValue();
    return status?.canBook ?? false;
  });

  // ============================================================================
  // USER API METHODS
  // ============================================================================

  /**
   * Load current user's verification status.
   * Caches result and updates status$ observable.
   *
   * @param forceRefresh - Skip cache and fetch fresh data
   */
  loadStatus(forceRefresh = false): void {
    // Return cached if available and not forced refresh
    if (!forceRefresh && this.statusSubject.getValue()) {
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.http
      .get<RenterVerificationProfile>(`${this.API_BASE}/me/verification`)
      .pipe(
        tap((status) => {
          this.statusSubject.next(status);
          this.loading.set(false);

          // Auto-start polling if pending review
          if (status.status === 'PENDING_REVIEW') {
            this.startPolling();
          }
        }),
        catchError((err) => {
          this.loading.set(false);
          // 404 means not started yet, treat as valid state
          if (err.status === 404) {
            const notStarted: RenterVerificationProfile = {
              userId: 0,
              fullName: '',
              email: '',
              status: 'NOT_STARTED',
              statusDisplay: 'Nije započeto',
              canBook: false,
              bookingBlockedReason: 'Vozačka dozvola nije verifikovana',
              maskedLicenseNumber: null,
              licenseExpiryDate: null,
              daysUntilExpiry: null,
              expiryWarning: false,
              licenseCountry: null,
              licenseCategories: null,
              licenseTenureMonths: null,
              submittedAt: null,
              verifiedAt: null,
              verifiedByName: null,
              riskLevel: null,
              riskLevelDisplay: null,
              documents: [],
              requiredDocumentsComplete: false,
              missingDocuments: [],
              canSubmit: true,
              estimatedWaitTime: null,
              rejectionReason: null,
              nextSteps: 'Otpremite vozačku dozvolu',
            };
            this.statusSubject.next(notStarted);
            return EMPTY;
          }
          this.error.set('Greška pri učitavanju statusa verifikacije');
          return EMPTY;
        })
      )
      .subscribe();
  }

  /**
   * Get verification status observable (with auto-load).
   * Loads from backend if not already cached.
   *
   * @returns Observable of verification profile
   */
  getStatus$(): Observable<RenterVerificationProfile | null> {
    if (!this.statusSubject.getValue()) {
      this.loadStatus();
    }
    return this.status$;
  }

  /**
   * Submit driver license documents for verification.
   *
   * SECURITY NOTES:
   * - Uses FormData (multipart/form-data) NOT base64 JSON
   * - Files are not logged or stored locally
   * - Progress is tracked via HttpEventType
   *
   * @param licenseFront - Front side of license image
   * @param licenseBack - Back side of license image
   * @param expiryDate - Optional manual expiry date (ISO format)
   * @param selfie - Optional selfie for liveness verification
   * @returns Observable of updated verification profile
   */
  submitLicense(
    licenseFront: File,
    licenseBack: File,
    expiryDate?: string,
    selfie?: File
  ): Observable<RenterVerificationProfile> {
    // Client-side validation
    const validationError = this.validateLicenseFiles(licenseFront, licenseBack);
    if (validationError) {
      return throwError(() => new Error(validationError));
    }

    const formData = new FormData();
    formData.append('licenseFront', licenseFront);
    formData.append('licenseBack', licenseBack);
    if (expiryDate) {
      formData.append('expiryDate', expiryDate);
    }
    if (selfie) {
      formData.append('selfie', selfie);
    }

    this.isUploading.set(true);
    this.uploadProgress.set(0);
    this.error.set(null);

    return this.http
      .post<RenterVerificationProfile>(
        `${this.API_BASE}/me/verification/license/submit`,
        formData,
        {
          reportProgress: true,
          observe: 'events',
        }
      )
      .pipe(
        tap((event) => {
          if (event.type === HttpEventType.UploadProgress) {
            const progress = event.total ? Math.round((100 * event.loaded) / event.total) : 0;
            this.uploadProgress.set(progress);
          }
        }),
        filter((event) => event.type === HttpEventType.Response),
        map((event: any) => event.body as RenterVerificationProfile),
        tap((profile) => {
          this.statusSubject.next(profile);

          // Start polling if pending review
          if (profile.status === 'PENDING_REVIEW') {
            this.startPolling();
          }
        }),
        finalize(() => {
          this.isUploading.set(false);
          this.uploadProgress.set(0);
        }),
        catchError((err) => {
          this.isUploading.set(false);
          this.uploadProgress.set(0);
          return this.handleUploadError(err);
        })
      );
  }

  /**
   * Resubmit license after rejection.
   * Internally calls submitLicense; backend tracks as resubmission via audit log.
   *
   * @param licenseFront - New front image
   * @param licenseBack - New back image
   * @param expiryDate - Optional manual expiry
   * @returns Observable of updated verification profile
   */
  resubmitLicense(
    licenseFront: File,
    licenseBack: File,
    expiryDate?: string
  ): Observable<RenterVerificationProfile> {
    return this.submitLicense(licenseFront, licenseBack, expiryDate);
  }

  /**
   * Check if user is eligible to book a car.
   * Called before showing booking form as a pre-flight check.
   *
   * NOTE: This is client-side convenience only.
   * Final eligibility is enforced in BookingService.createBooking() on backend.
   *
   * @param tripEndDate - Optional trip end date to check license expiry against
   * @returns Observable of eligibility result
   */
  checkBookingEligibility(tripEndDate?: string): Observable<BookingEligibility> {
    let params = new HttpParams();
    if (tripEndDate) {
      params = params.set('tripEndDate', tripEndDate);
    }

    return this.http
      .get<BookingEligibility>(`${this.API_BASE}/me/verification/booking-eligible`, { params })
      .pipe(
        shareReplay({ bufferSize: 1, refCount: true }),
        catchError((err) => {
          // On error, return not eligible with generic message
          return of<BookingEligibility>({
            eligible: false,
            blockedReason: 'Unable to verify eligibility',
            messageSr: 'Nije moguće proveriti podobnost. Pokušajte ponovo.',
            messageEn: 'Unable to verify eligibility. Please try again.',
            licenseExpiresBeforeTripEnd: false,
            daysUntilExpiry: null,
          });
        })
      );
  }

  /**
   * Force refresh verification status from backend.
   * Use after admin action or user resubmission.
   */
  refreshStatus(): void {
    this.loadStatus(true);
  }

  /**
   * Clear cached verification status.
   * Call on logout or user switch.
   */
  clearCache(): void {
    this.statusSubject.next(null);
    this.stopPolling();
    this.error.set(null);
  }

  // ============================================================================
  // POLLING METHODS
  // ============================================================================

  /**
   * Start polling for status updates during PENDING_REVIEW.
   * Uses exponential backoff: 5s, 10s, 20s, 30s (max).
   * Stops automatically when status changes or max attempts reached.
   */
  startPolling(): void {
    if (this.isPolling) {
      return;
    }

    this.isPolling = true;
    this.pollAttempt = 0;

    this.poll();
  }

  /**
   * Stop polling manually.
   * Use when navigating away or on user request.
   */
  stopPolling(): void {
    this.stopPolling$.next();
    this.isPolling = false;
    this.pollAttempt = 0;
  }

  /**
   * Execute a single poll with backoff.
   */
  private poll(): void {
    if (!this.isPolling) {
      return;
    }

    // Calculate backoff interval
    const interval = Math.min(
      this.BASE_POLL_INTERVAL * Math.pow(2, Math.floor(this.pollAttempt / 3)),
      this.MAX_POLL_INTERVAL
    );

    timer(interval)
      .pipe(
        takeUntil(this.stopPolling$),
        switchMap(() =>
          this.http.get<RenterVerificationProfile>(`${this.API_BASE}/me/verification`)
        ),
        catchError(() => {
          // On error, continue polling but increment attempt
          return EMPTY;
        })
      )
      .subscribe({
        next: (status) => {
          this.statusSubject.next(status);

          // Stop polling if status is no longer pending
          if (status.status !== 'PENDING_REVIEW') {
            this.stopPolling();
            return;
          }

          // Check max attempts
          this.pollAttempt++;
          if (this.pollAttempt >= this.MAX_POLL_ATTEMPTS) {
            this.stopPolling();
            return;
          }

          // Continue polling
          this.poll();
        },
        error: () => {
          // Increment and continue
          this.pollAttempt++;
          if (this.pollAttempt < this.MAX_POLL_ATTEMPTS) {
            this.poll();
          } else {
            this.stopPolling();
          }
        },
      });
  }

  // ============================================================================
  // PRIVATE HELPERS
  // ============================================================================

  /**
   * Validate license files on client before upload.
   *
   * @param front - Front image file
   * @param back - Back image file
   * @returns Error message or null if valid
   */
  private validateLicenseFiles(front: File, back: File): string | null {
    const MAX_SIZE = 10 * 1024 * 1024; // 10 MB
    const ALLOWED_TYPES = ['image/jpeg', 'image/jpg', 'image/png'];

    if (!front || !back) {
      return 'Potrebne su obe strane vozačke dozvole';
    }

    for (const file of [front, back]) {
      if (file.size > MAX_SIZE) {
        return `Fajl "${file.name}" je prevelik. Maksimalna veličina: 10MB`;
      }
      if (!ALLOWED_TYPES.includes(file.type)) {
        return `Fajl "${file.name}" ima nedozvoljen format. Dozvoljeni: JPEG, PNG`;
      }
    }

    return null;
  }

  /**
   * Handle upload errors with user-friendly messages.
   */
  private handleUploadError(err: any): Observable<never> {
    let message = 'Otpremanje nije uspelo. Pokušajte ponovo.';

    if (err.status === 400) {
      message = err.error?.message || 'Nevažeći fajlovi. Proverite format i veličinu.';
    } else if (err.status === 413) {
      message = 'Fajlovi su preveliki. Maksimalna veličina: 10MB';
    } else if (err.status === 429) {
      message = 'Previše pokušaja. Pokušajte ponovo za sat vremena.';
    } else if (err.status === 0) {
      message = 'Greška mreže. Proverite internet konekciju.';
    }

    this.error.set(message);
    return throwError(() => new Error(message));
  }
}
