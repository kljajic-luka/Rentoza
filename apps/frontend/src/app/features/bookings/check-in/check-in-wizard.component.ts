/**
 * Check-In Wizard Container Component
 *
 * Multi-step wizard for handling the complete check-in handshake protocol:
 * 1. Host Phase: Vehicle photos, odometer, fuel level
 * 2. Guest Phase: Review conditions, acknowledge
 * 3. Handshake: Both parties confirm
 * 4. Complete: Check-in done
 */
import {
  Component,
  OnInit,
  OnDestroy,
  inject,
  signal,
  computed,
  effect,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { MatStepperModule } from '@angular/material/stepper';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { STEPPER_GLOBAL_OPTIONS } from '@angular/cdk/stepper';

import {
  CheckInService,
  CheckInPhase,
  RenderDecision,
} from '../../../core/services/check-in.service';
import { GeolocationService } from '../../../core/services/geolocation.service';
import { OfflineQueueService } from '../../../core/services/offline-queue.service';
import { AuthService } from '../../../core/auth/auth.service';
import { RenterVerificationService } from '../../../core/services/renter-verification.service';
import { BookingEligibility } from '../../../core/models/renter-verification.model';

import { HostCheckInComponent } from './host-check-in.component';
import { GuestCheckInComponent } from './guest-check-in.component';
import { HandshakeComponent } from './handshake.component';
import { CheckInCompleteComponent } from './check-in-complete.component';
import { CheckInWaitingComponent } from './check-in-waiting.component';
import { environment } from '@environments/environment';
import { BookingService } from '@core/services/booking.service';
import { AgreementSummary } from '@core/models/booking.model';
import { canProceedToCheckInFromSummary } from '@core/utils/agreement-summary.utils';

@Component({
  selector: 'app-check-in-wizard',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    MatStepperModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatSnackBarModule,
    MatDialogModule,
    HostCheckInComponent,
    GuestCheckInComponent,
    HandshakeComponent,
    CheckInCompleteComponent,
    CheckInWaitingComponent,
  ],
  providers: [
    {
      provide: STEPPER_GLOBAL_OPTIONS,
      useValue: { showError: true, displayDefaultIndicatorType: false },
    },
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="check-in-wizard">
      <!-- Header -->
      <header class="wizard-header">
        <button mat-icon-button (click)="navigateBack()" aria-label="Nazad">
          <mat-icon>arrow_back</mat-icon>
        </button>
        <h1>Check-in</h1>
        <div class="header-spacer"></div>

        <!-- Offline indicator -->
        @if (offlineQueueService.isOnline() === false) {
          <div class="offline-badge">
            <mat-icon>cloud_off</mat-icon>
            <span>Offline</span>
          </div>
        }

        <!-- Pending uploads badge -->
        @if (offlineQueueService.queueLength() > 0) {
          <div class="pending-uploads-badge" [class.syncing]="offlineQueueService.isProcessing()">
            <mat-icon>{{ offlineQueueService.isProcessing() ? 'sync' : 'cloud_upload' }}</mat-icon>
            <span>{{ offlineQueueService.queueLength() }}</span>
          </div>
        }
      </header>

      <!-- Loading state (derived from renderDecision signal) -->
      @if (checkInService.renderDecision() === 'LOADING') {
        <div class="loading-container" role="status" aria-live="polite">
          <mat-progress-bar mode="indeterminate"></mat-progress-bar>
          <p>Učitavanje check-in statusa...</p>
        </div>
      }

      <!-- Error state -->
      @if (checkInService.error()) {
        <div class="error-container" role="alert">
          <mat-icon>error</mat-icon>
          <h2>Greška</h2>
          <p>{{ checkInService.error() }}</p>
          <button mat-raised-button color="primary" (click)="retryLoad()">
            <mat-icon>refresh</mat-icon>
            Pokušaj ponovo
          </button>
        </div>
      }

      @if (!checkInService.error() && agreementGateState() === 'blocked') {
        <div class="waiting-container agreement-blocked">
          <mat-icon>gavel</mat-icon>
          <h2>Check-in je blokiran dok se ugovor ne reši</h2>
          <p>{{ agreementGateMessage() }}</p>
          <div class="blocked-actions">
            <button mat-raised-button color="primary" (click)="openBookingAgreement()">
              <mat-icon>receipt_long</mat-icon>
              Otvori rezervaciju
            </button>
            <button mat-stroked-button color="primary" (click)="retryLoad()">
              <mat-icon>refresh</mat-icon>
              Osveži status
            </button>
          </div>
        </div>
      }

      <!-- Main content - Role-Aware State Machine via renderDecision -->
      @if (checkInService.renderDecision() !== 'LOADING' && !checkInService.error() && agreementGateState() === 'ready') {
        <div class="wizard-content" aria-live="polite">
          <!-- Progress indicator -->
          <div class="progress-steps">
            <div
              class="step"
              [class.active]="isStepActiveByRender('HOST')"
              [class.completed]="isStepCompletedByRender('HOST')"
            >
              <div class="step-icon">
                @if (isStepCompletedByRender('HOST')) {
                  <mat-icon>check</mat-icon>
                } @else {
                  <span>1</span>
                }
              </div>
              <span class="step-label">Domaćin</span>
            </div>

            <div class="step-connector" [class.completed]="isStepCompletedByRender('HOST')"></div>

            <div
              class="step"
              [class.active]="isStepActiveByRender('GUEST')"
              [class.completed]="isStepCompletedByRender('GUEST')"
            >
              <div class="step-icon">
                @if (isStepCompletedByRender('GUEST')) {
                  <mat-icon>check</mat-icon>
                } @else {
                  <span>2</span>
                }
              </div>
              <span class="step-label">Gost</span>
            </div>

            <div class="step-connector" [class.completed]="isStepCompletedByRender('GUEST')"></div>

            <div
              class="step"
              [class.active]="isStepActiveByRender('HANDSHAKE')"
              [class.completed]="isStepCompletedByRender('HANDSHAKE')"
            >
              <div class="step-icon">
                @if (isStepCompletedByRender('HANDSHAKE')) {
                  <mat-icon>check</mat-icon>
                } @else {
                  <span>3</span>
                }
              </div>
              <span class="step-label">Potvrda</span>
            </div>
          </div>

          <!-- Location status -->
          @if (showLocationStatus()) {
            <div
              class="location-status"
              [class.error]="geolocationService.hasError() && !geolocationService.hasPosition()"
              [class.warning]="geolocationService.isAccuracyPoor()"
            >
              @if (geolocationService.isLoading() && !geolocationService.hasPosition()) {
                <mat-icon class="pulse">location_searching</mat-icon>
                <span>Traženje GPS signala...</span>
              } @else if (geolocationService.hasPosition()) {
                <mat-icon>my_location</mat-icon>
                <span
                  >GPS aktivan (±{{ geolocationService.position()?.accuracy?.toFixed(0) }}m)</span
                >
              } @else if (geolocationService.hasError()) {
                <mat-icon>location_off</mat-icon>
                <span>{{ geolocationService.error()?.message }}</span>
                <button mat-button (click)="retryLocation()">Pokušaj ponovo</button>
              } @else {
                <mat-icon>location_searching</mat-icon>
                <span>Čekanje GPS...</span>
              }
            </div>
          }

          <!-- ================================================================
             ROLE-AWARE STATE MACHINE (renderDecision)
             ================================================================
             This switch is EXHAUSTIVE and uses the single source of truth.
             No role checks needed here - renderDecision already incorporates role.
        -->
          @switch (checkInService.renderDecision()) {
            @case ('HOST_EDIT') {
              <!-- Host can upload photos and submit -->
              <app-host-check-in
                [bookingId]="bookingId()"
                [status]="checkInService.currentStatus()"
                (completed)="onHostPhaseCompleted()"
              ></app-host-check-in>
            }
            @case ('HOST_WAITING') {
              <!-- Host waiting for guest - can review their submission -->
              @if (showingReview()) {
                <app-host-check-in
                  [bookingId]="bookingId()"
                  [status]="checkInService.currentStatus()"
                  [readOnly]="true"
                  (backFromReview)="exitReviewMode()"
                ></app-host-check-in>
              } @else {
                <app-check-in-waiting
                  [status]="checkInService.currentStatus()"
                  [title]="waitingTitle()"
                  [message]="waitingMessage()"
                  icon="check_circle"
                  [iconType]="'success'"
                  [nextSteps]="hostWaitingSteps"
                  [showReviewButton]="true"
                  [showSubmittedData]="true"
                  [animate]="true"
                  (refresh)="retryLoad()"
                  (reviewData)="enterReviewMode()"
                ></app-check-in-waiting>
              }
            }
            @case ('HOST_REVIEW') {
              <!-- Explicit HOST_REVIEW state if needed -->
              <app-host-check-in
                [bookingId]="bookingId()"
                [status]="checkInService.currentStatus()"
                [readOnly]="true"
                (backFromReview)="exitReviewMode()"
              ></app-host-check-in>
            }
            @case ('GUEST_WAITING') {
              <!-- Guest waiting for host to complete -->
              <app-check-in-waiting
                [status]="checkInService.currentStatus()"
                [title]="waitingTitle()"
                [message]="waitingMessage()"
                icon="schedule"
                [iconType]="'waiting'"
                [nextSteps]="guestWaitingSteps"
                [animate]="true"
                (refresh)="retryLoad()"
              ></app-check-in-waiting>
            }
            @case ('GUEST_EDIT') {
              <!-- Guest can review photos and acknowledge condition -->
              <app-guest-check-in
                [bookingId]="bookingId()"
                [status]="checkInService.currentStatus()"
                (completed)="onGuestPhaseCompleted()"
              ></app-guest-check-in>
            }
            @case ('HANDSHAKE') {
              <!-- Both parties confirming physical handoff -->
              <app-handshake
                [bookingId]="bookingId()"
                [status]="checkInService.currentStatus()"
                (completed)="onHandshakeCompleted()"
              ></app-handshake>
            }
            @case ('COMPLETE') {
              <!-- Trip has started -->
              <app-check-in-complete
                [bookingId]="bookingId()"
                [status]="checkInService.currentStatus()"
              ></app-check-in-complete>
            }
            @case ('NOT_READY') {
              <!-- Check-in window not yet open -->
              <div class="waiting-container">
                <mat-icon>schedule</mat-icon>
                <h2>Check-in još nije dostupan</h2>
                <p>Check-in postaje dostupan 2 sata pre početka rezervacije.</p>
                @if (checkInService.currentStatus()?.bookingStartTime) {
                  <p class="start-time">
                    Početak:
                    {{
                      checkInService.currentStatus()?.bookingStartTime | date: 'dd.MM.yyyy HH:mm'
                    }}
                  </p>
                }
                <button
                  mat-stroked-button
                  color="primary"
                  (click)="retryLoad()"
                  class="refresh-btn"
                >
                  <mat-icon>refresh</mat-icon>
                  Osveži status
                </button>
              </div>
            }
            @default {
              <!-- Fallback for any unhandled state (should never happen with exhaustive switch) -->
              <div class="waiting-container">
                <mat-icon>help_outline</mat-icon>
                <h2>Nepoznato stanje</h2>
                <p>Došlo je do neočekivane greške. Pokušajte osvežiti stranicu.</p>
                <button mat-raised-button color="primary" (click)="retryLoad()">
                  <mat-icon>refresh</mat-icon>
                  Osveži
                </button>
              </div>
            }
          }
        </div>
      }
    </div>
  `,
  styles: [
    `
      .check-in-wizard {
        min-height: 100vh;
        background: var(--color-bg, #f5f5f5);
        display: flex;
        flex-direction: column;
      }

      .wizard-header {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 12px 16px;
        background: var(
          --toolbar-bg,
          linear-gradient(135deg, var(--brand-primary), var(--color-primary-hover))
        );
        color: var(--toolbar-color, white);
        position: sticky;
        top: 0;
        z-index: 100;
      }

      .wizard-header h1 {
        font-size: 20px;
        font-weight: 500;
        margin: 0;
      }

      .header-spacer {
        flex: 1;
      }

      .offline-badge,
      .pending-uploads-badge {
        display: flex;
        align-items: center;
        gap: 4px;
        padding: 4px 8px;
        border-radius: 16px;
        background: rgba(255, 255, 255, 0.2);
        font-size: 12px;
      }

      .pending-uploads-badge.syncing mat-icon {
        animation: spin 1s linear infinite;
      }

      @keyframes spin {
        100% {
          transform: rotate(360deg);
        }
      }

      .loading-container {
        flex: 1;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 16px;
        padding: 32px;
        color: var(--color-text-primary, #212121);
      }

      .loading-container mat-progress-bar {
        width: 200px;
      }

      .error-container {
        flex: 1;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 16px;
        padding: 32px;
        text-align: center;
        color: var(--color-text-primary, #212121);
      }

      .error-container mat-icon {
        font-size: 64px;
        width: 64px;
        height: 64px;
        color: var(--warn-color, #f44336);
      }

      .wizard-content {
        flex: 1;
        display: flex;
        flex-direction: column;
        padding: 16px;
      }

      /* Progress steps */
      .progress-steps {
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 16px 0 24px;
      }

      .step {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 8px;
      }

      .step-icon {
        width: 36px;
        height: 36px;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        background: var(--color-surface-muted, #e0e0e0);
        color: var(--color-text-muted, #757575);
        font-weight: 500;
        transition: all 0.3s ease;
      }

      .step.active .step-icon {
        background: var(--brand-primary);
        color: white;
      }

      .step.completed .step-icon {
        background: var(--success-color, #4caf50);
        color: white;
      }

      .step-label {
        font-size: 12px;
        color: var(--color-text-muted, #757575);
      }

      .step.active .step-label {
        color: var(--brand-primary);
        font-weight: 500;
      }

      .step.completed .step-label {
        color: var(--success-color, #4caf50);
      }

      .step-connector {
        width: 60px;
        height: 2px;
        background: var(--color-surface-muted, #e0e0e0);
        margin: 0 8px;
        margin-bottom: 28px;
        transition: background 0.3s ease;
      }

      .step-connector.completed {
        background: var(--success-color, #4caf50);
      }

      /* Location status */
      .location-status {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 8px 12px;
        margin-bottom: 16px;
        border-radius: 8px;
        background: var(--info-bg, rgba(59, 130, 246, 0.12));
        color: var(--info-color, #1565c0);
        font-size: 14px;
      }

      .location-status.error {
        background: var(--warn-bg, rgba(244, 67, 54, 0.12));
        color: var(--warn-color, #c62828);
      }

      .location-status.warning {
        background: var(--orange-bg, rgba(255, 152, 0, 0.12));
        color: var(--orange-color, #e65100);
      }

      .location-status .pulse {
        animation: pulse 1.5s ease-in-out infinite;
      }

      @keyframes pulse {
        0%,
        100% {
          opacity: 1;
        }
        50% {
          opacity: 0.5;
        }
      }

      /* Waiting container */
      .waiting-container {
        flex: 1;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 16px;
        padding: 32px;
        text-align: center;
      }

      .waiting-container mat-icon {
        font-size: 64px;
        width: 64px;
        height: 64px;
        color: var(--brand-primary);
      }

      .waiting-container h2 {
        margin: 0;
        color: var(--color-text-primary, #212121);
      }

      .waiting-container p {
        margin: 0;
        color: var(--color-text-muted, #757575);
      }

      .start-time {
        font-weight: 500;
        color: var(--brand-primary) !important;
      }

      /* Mobile optimizations */
      @media (max-width: 599px) {
        .step-connector {
          width: 40px;
        }

        .step-label {
          font-size: 11px;
        }
      }
    `,
  ],
})
export class CheckInWizardComponent implements OnInit, OnDestroy {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);
  private authService = inject(AuthService);
  private verificationService = inject(RenterVerificationService);
  private bookingService = inject(BookingService);

  checkInService = inject(CheckInService);
  geolocationService = inject(GeolocationService);
  offlineQueueService = inject(OfflineQueueService);

  // Booking ID from route
  bookingId = signal<number>(0);

  // Review mode: Host can review their submitted data in read-only mode
  showingReview = signal(false);
  agreementGateState = signal<'loading' | 'ready' | 'blocked'>('loading');
  agreementGateMessage = signal('');

  // Step text for waiting screens
  readonly guestWaitingSteps = [
    'Domaćin uploaduje fotografije vozila',
    'Vi pregledate stanje vozila',
    'Potvrda preuzimanja vozila',
  ];

  readonly hostWaitingSteps = [
    'Gost pregleda fotografije',
    'Gost potvrđuje stanje vozila',
    'Finalna potvrda preuzimanja',
  ];

  // Computed: should show location status (only if location is required)
  showLocationStatus = computed(() => {
    // Skip location UI if disabled in environment
    if (environment.checkIn?.requireLocation === false) {
      return false;
    }
    const decision = this.checkInService.renderDecision();
    return decision === 'HOST_EDIT' || decision === 'GUEST_EDIT';
  });

  /**
   * Dynamic waiting screen title based on renderDecision state.
   * Provides role-aware messaging.
   */
  waitingTitle = computed((): string => {
    const decision = this.checkInService.renderDecision();
    switch (decision) {
      case 'HOST_WAITING':
        return 'Vaš deo je završen!';
      case 'GUEST_WAITING':
        return 'Domaćin priprema vozilo';
      default:
        return 'Čekanje...';
    }
  });

  /**
   * Dynamic waiting screen message based on renderDecision state.
   * Provides context-specific instructions.
   */
  waitingMessage = computed((): string => {
    const decision = this.checkInService.renderDecision();
    switch (decision) {
      case 'HOST_WAITING':
        return 'Čeka se da gost pregleda i potvrdi stanje vozila.';
      case 'GUEST_WAITING':
        return 'Sačekajte da domaćin unese fotografije i podatke o vozilu.';
      default:
        return 'Molimo sačekajte...';
    }
  });

  // Lifecycle hooks
  ngOnInit(): void {
    // Extract booking ID from route
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.bookingId.set(parseInt(id, 10));
      // Subscribe to loadStatus to trigger the API call
      this.checkInService.loadStatus(this.bookingId()).subscribe({
        next: (status) => {
          console.log('[CheckInWizard] Status loaded:', status);
          this.loadAgreementGate(status.host === true, status.guest === true);

          // Re-validate driver license eligibility at check-in time
          // Only for guests (renters) - hosts don't need license validation
          // Use tripStartScheduled as proxy for trip duration validation
          if (status.guest && status.tripStartScheduled) {
            this.validateLicenseForCheckIn(status.tripStartScheduled);
          }
        },
        error: (err) => {
          console.error('[CheckInWizard] Failed to load status:', err);
        },
      });
    }

    // Start location tracking if required and not disabled in dev mode
    if (environment.checkIn?.requireLocation !== false) {
      this.startLocationTracking();
    } else {
      console.log('[CheckInWizard] Location tracking disabled in environment config');
    }
  }

  /**
   * Validates driver license eligibility at check-in time.
   * Ensures license hasn't expired since booking was made.
   *
   * @param tripEndDate - The booking end date to validate against
   */
  private validateLicenseForCheckIn(tripEndDate: string): void {
    this.verificationService.checkBookingEligibility(tripEndDate).subscribe({
      next: (eligibility: BookingEligibility) => {
        if (!eligibility.eligible) {
          console.warn('[CheckInWizard] License eligibility failed:', eligibility.blockedReason);

          // Show error and block check-in
          const message =
            eligibility.messageSr || 'Vaša vozačka dozvola više nije važeća za ovo putovanje.';

          this.snackBar
            .open(message, 'Kontaktiraj podršku', {
              duration: 10000,
              panelClass: ['snackbar-error'],
            })
            .onAction()
            .subscribe(() => {
              // Navigate to support or verification page
              this.router.navigate(['/verify-license']);
            });

          // Redirect away from check-in
          setTimeout(() => {
            this.router.navigate(['/bookings', this.bookingId()]);
          }, 3000);
        } else if (eligibility.licenseExpiresBeforeTripEnd) {
          // Warning: License expires during trip
          const daysUntil = eligibility.daysUntilExpiry ?? 0;
          this.snackBar.open(
            `Upozorenje: Vaša vozačka dozvola ističe za ${daysUntil} dana, pre završetka putovanja.`,
            'Razumem',
            { duration: 8000, panelClass: ['snackbar-warning'] },
          );
        }
      },
      error: (err) => {
        // On error, log but don't block (backend enforces final validation)
        console.error('[CheckInWizard] License validation error:', err);
      },
    });
  }

  ngOnDestroy(): void {
    // Stop polling when leaving
    this.checkInService.stopPolling();
    this.geolocationService.stopWatching();
  }

  // Navigation
  navigateBack(): void {
    // Owners go to their bookings list, guests go to booking details
    if (this.authService.hasAnyRole('OWNER')) {
      this.router.navigate(['/owner/bookings']);
    } else {
      this.router.navigate(['/bookings', this.bookingId()]);
    }
  }

  retryLoad(): void {
    this.agreementGateState.set('loading');
    this.checkInService.loadStatus(this.bookingId()).subscribe({
      next: (status) => {
        console.log('[CheckInWizard] Status refreshed:', status.status);
        this.loadAgreementGate(status.host === true, status.guest === true);
      },
      error: (err) => {
        console.error('[CheckInWizard] Failed to refresh status:', err);
      },
    });
  }

  // Location
  private startLocationTracking(): void {
    // Request location permission and start continuous watching
    this.geolocationService.getCurrentPosition().then(
      (position) => {
        console.log('[CheckInWizard] GPS position acquired:', position);
        // Start continuous watching after initial position
        this.geolocationService.startWatching();
      },
      (error) => {
        console.warn('[CheckInWizard] GPS error:', error);
      },
    );
  }

  retryLocation(): void {
    this.geolocationService.getCurrentPosition().then(
      () => this.geolocationService.startWatching(),
      (err) => console.warn('[CheckInWizard] Retry GPS error:', err),
    );
  }

  // Step helpers
  isStepActive(step: 'HOST' | 'GUEST' | 'HANDSHAKE'): boolean {
    const phase = this.checkInService.currentPhase();
    switch (step) {
      case 'HOST':
        return phase === 'HOST_PHASE';
      case 'GUEST':
        return phase === 'GUEST_PHASE';
      case 'HANDSHAKE':
        return phase === 'HANDSHAKE';
      default:
        return false;
    }
  }

  isStepCompleted(step: 'HOST' | 'GUEST' | 'HANDSHAKE'): boolean {
    const status = this.checkInService.currentStatus();
    if (!status) return false;

    switch (step) {
      case 'HOST':
        return !!status.hostCheckedIn;
      case 'GUEST':
        return !!status.guestAcknowledged;
      case 'HANDSHAKE':
        return !!status.handshakeComplete;
      default:
        return false;
    }
  }

  /**
   * Step active check using renderDecision signal.
   * Maps renderDecision states to visual progress indicator.
   */
  isStepActiveByRender(step: 'HOST' | 'GUEST' | 'HANDSHAKE'): boolean {
    const decision = this.checkInService.renderDecision();
    switch (step) {
      case 'HOST':
        return (
          decision === 'HOST_EDIT' || decision === 'HOST_WAITING' || decision === 'HOST_REVIEW'
        );
      case 'GUEST':
        return decision === 'GUEST_WAITING' || decision === 'GUEST_EDIT';
      case 'HANDSHAKE':
        return decision === 'HANDSHAKE';
      default:
        return false;
    }
  }

  /**
   * Step completion check using status data.
   * Determined by backend-provided completion flags.
   */
  isStepCompletedByRender(step: 'HOST' | 'GUEST' | 'HANDSHAKE'): boolean {
    const status = this.checkInService.currentStatus();
    const decision = this.checkInService.renderDecision();
    if (!status) return false;

    switch (step) {
      case 'HOST':
        // Host step is complete when host has checked in AND we're past HOST_EDIT
        return (
          !!status.hostCheckInComplete &&
          (decision === 'GUEST_WAITING' ||
            decision === 'GUEST_EDIT' ||
            decision === 'HANDSHAKE' ||
            decision === 'COMPLETE')
        );
      case 'GUEST':
        // Guest step is complete when guest acknowledged AND we're at/past HANDSHAKE
        return !!status.guestAcknowledged && (decision === 'HANDSHAKE' || decision === 'COMPLETE');
      case 'HANDSHAKE':
        // Handshake complete means trip has started
        return decision === 'COMPLETE';
      default:
        return false;
    }
  }

  // Phase completion handlers
  onHostPhaseCompleted(): void {
    this.snackBar.open('Check-in uspešno poslat gostu!', 'OK', {
      duration: 3000,
      panelClass: 'success-snackbar',
    });

    // Reload status to get updated phase (HOST_COMPLETE -> GUEST_PHASE)
    // This triggers the transition to the waiting screen
    this.checkInService.loadStatus(this.bookingId()).subscribe({
      next: (status) => {
        console.log('[CheckInWizard] Status updated after host submission:', status.status);
      },
      error: (err) => {
        console.error('[CheckInWizard] Failed to refresh status after submission:', err);
      },
    });
  }

  onGuestPhaseCompleted(): void {
    this.snackBar.open('Stanje vozila potvrđeno!', 'OK', {
      duration: 3000,
      panelClass: 'success-snackbar',
    });
  }

  onHandshakeCompleted(): void {
    this.snackBar.open('Check-in uspešno završen! 🎉', 'Odlično', {
      duration: 5000,
      panelClass: 'success-snackbar',
    });
  }

  // Review mode handlers
  enterReviewMode(): void {
    this.showingReview.set(true);
  }

  exitReviewMode(): void {
    this.showingReview.set(false);
  }

  openBookingAgreement(): void {
    this.router.navigate(['/bookings', this.bookingId()], {
      queryParams: { agreementRequired: '1' },
    });
  }

  private loadAgreementGate(isHost: boolean, isGuest: boolean): void {
    this.bookingService.resolveCheckInAgreementGate(this.bookingId()).subscribe({
      next: (gate) => {
        if (gate.state === 'allowed') {
          this.agreementGateState.set('ready');
          this.agreementGateMessage.set('');
          return;
        }

        this.agreementGateState.set('blocked');
        if (gate.state === 'retry') {
          this.agreementGateMessage.set(
            'Trenutno ne možemo da proverimo status ugovora o iznajmljivanju. Osvežite podatke i pokušajte ponovo pre nastavka check-in procesa.',
          );
          return;
        }

        this.agreementGateMessage.set(
          this.buildAgreementGateMessage(gate.summary, isHost, isGuest),
        );
      },
    });
  }

  private buildAgreementGateMessage(
    summary: AgreementSummary | null | undefined,
    isHost: boolean,
    isGuest: boolean,
  ): string {
    if (!summary) {
      return 'Status ugovora o iznajmljivanju još nije dostupan za ovu rezervaciju.';
    }
    if (summary.workflowStatus === 'AGREEMENT_EXPIRED_OWNER_BREACH') {
      return 'Ugovor o iznajmljivanju je istekao jer domaćin nije prihvatio uslove na vreme.';
    }
    if (summary.workflowStatus === 'AGREEMENT_EXPIRED_RENTER_BREACH') {
      return 'Ugovor o iznajmljivanju je istekao i check-in više nije dostupan iz ove rute.';
    }
    if (summary.workflowStatus === 'AGREEMENT_EXPIRED_BOTH_PARTIES') {
      return 'Ugovor o iznajmljivanju je istekao jer nije prihvaćen na vreme od obe strane.';
    }
    if (isHost && summary.currentActorNeedsAcceptance) {
      return 'Kao domaćin prvo morate prihvatiti ugovor o iznajmljivanju pre pripreme vozila.';
    }
    if (isGuest && summary.currentActorNeedsAcceptance) {
      return 'Kao gost prvo morate prihvatiti ugovor o iznajmljivanju pre nastavka check-in procesa.';
    }
    return 'Druga strana još nije prihvatila ugovor o iznajmljivanju. Check-in ostaje blokiran dok se ugovor ne reši.';
  }
}
