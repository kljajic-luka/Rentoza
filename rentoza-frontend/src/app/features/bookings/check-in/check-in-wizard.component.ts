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

import { CheckInService, CheckInPhase } from '../../../core/services/check-in.service';
import { GeolocationService } from '../../../core/services/geolocation.service';
import { OfflineQueueService } from '../../../core/services/offline-queue.service';
import { AuthService } from '../../../core/auth/auth.service';

import { HostCheckInComponent } from './host-check-in.component';
import { GuestCheckInComponent } from './guest-check-in.component';
import { HandshakeComponent } from './handshake.component';
import { CheckInCompleteComponent } from './check-in-complete.component';
import { CheckInWaitingComponent } from './check-in-waiting.component';
import { environment } from '@environments/environment';

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

      <!-- Loading state -->
      @if (checkInService.currentPhase() === 'LOADING') {
      <div class="loading-container">
        <mat-progress-bar mode="indeterminate"></mat-progress-bar>
        <p>Učitavanje check-in statusa...</p>
      </div>
      }

      <!-- Error state -->
      @if (checkInService.error()) {
      <div class="error-container">
        <mat-icon>error</mat-icon>
        <h2>Greška</h2>
        <p>{{ checkInService.error() }}</p>
        <button mat-raised-button color="primary" (click)="retryLoad()">
          <mat-icon>refresh</mat-icon>
          Pokušaj ponovo
        </button>
      </div>
      }

      <!-- Main content -->
      @if (checkInService.currentPhase() !== 'LOADING' && !checkInService.error()) {
      <div class="wizard-content">
        <!-- Progress indicator -->
        <div class="progress-steps">
          <div
            class="step"
            [class.active]="isStepActive('HOST')"
            [class.completed]="isStepCompleted('HOST')"
          >
            <div class="step-icon">
              @if (isStepCompleted('HOST')) {
              <mat-icon>check</mat-icon>
              } @else {
              <span>1</span>
              }
            </div>
            <span class="step-label">Domaćin</span>
          </div>

          <div class="step-connector" [class.completed]="isStepCompleted('HOST')"></div>

          <div
            class="step"
            [class.active]="isStepActive('GUEST')"
            [class.completed]="isStepCompleted('GUEST')"
          >
            <div class="step-icon">
              @if (isStepCompleted('GUEST')) {
              <mat-icon>check</mat-icon>
              } @else {
              <span>2</span>
              }
            </div>
            <span class="step-label">Gost</span>
          </div>

          <div class="step-connector" [class.completed]="isStepCompleted('GUEST')"></div>

          <div
            class="step"
            [class.active]="isStepActive('HANDSHAKE')"
            [class.completed]="isStepCompleted('HANDSHAKE')"
          >
            <div class="step-icon">
              @if (isStepCompleted('HANDSHAKE')) {
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
          <span>GPS aktivan (±{{ geolocationService.position()?.accuracy?.toFixed(0) }}m)</span>
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

        <!-- Phase content with role-aware rendering -->
        @switch (checkInService.currentPhase()) { @case ('HOST_PHASE') {
        <!-- HOST_PHASE: Host edits, Guest waits -->
        @if (checkInService.isHost()) { @if (showingReview()) {
        <app-host-check-in
          [bookingId]="bookingId()"
          [status]="checkInService.currentStatus()"
          [readOnly]="true"
          (backFromReview)="exitReviewMode()"
        >
        </app-host-check-in>
        } @else {
        <app-host-check-in
          [bookingId]="bookingId()"
          [status]="checkInService.currentStatus()"
          (completed)="onHostPhaseCompleted()"
        >
        </app-host-check-in>
        } } @else {
        <!-- Guest waiting for Host to finish -->
        <app-check-in-waiting
          [status]="checkInService.currentStatus()"
          title="Domaćin priprema vozilo"
          message="Sačekajte da domaćin unese fotografije i podatke o vozilu."
          icon="schedule"
          [iconType]="'waiting'"
          [nextSteps]="guestWaitingSteps"
          [animate]="true"
          (refresh)="retryLoad()"
        >
        </app-check-in-waiting>
        } } @case ('GUEST_PHASE') {
        <!-- GUEST_PHASE: Guest reviews, Host waits -->
        @if (checkInService.isGuest()) {
        <app-guest-check-in
          [bookingId]="bookingId()"
          [status]="checkInService.currentStatus()"
          (completed)="onGuestPhaseCompleted()"
        >
        </app-guest-check-in>
        } @else {
        <!-- Host waiting for Guest to review -->
        @if (showingReview()) {
        <app-host-check-in
          [bookingId]="bookingId()"
          [status]="checkInService.currentStatus()"
          [readOnly]="true"
          (backFromReview)="exitReviewMode()"
        >
        </app-host-check-in>
        } @else {
        <app-check-in-waiting
          [status]="checkInService.currentStatus()"
          title="Vaš deo je završen!"
          message="Čeka se da gost pregleda i potvrdi stanje vozila."
          icon="check_circle"
          [iconType]="'success'"
          [nextSteps]="hostWaitingSteps"
          [showReviewButton]="true"
          [showSubmittedData]="true"
          [animate]="true"
          (refresh)="retryLoad()"
          (reviewData)="enterReviewMode()"
        >
        </app-check-in-waiting>
        } } } @case ('HANDSHAKE') {
        <app-handshake
          [bookingId]="bookingId()"
          [status]="checkInService.currentStatus()"
          (completed)="onHandshakeCompleted()"
        >
        </app-handshake>
        } @case ('COMPLETE') {
        <app-check-in-complete [bookingId]="bookingId()" [status]="checkInService.currentStatus()">
        </app-check-in-complete>
        } @default {
        <!-- Waiting state -->
        <div class="waiting-container">
          <mat-icon>schedule</mat-icon>
          <h2>Check-in još nije dostupan</h2>
          <p>Check-in postaje dostupan 30 minuta pre početka rezervacije.</p>
          @if (checkInService.currentStatus()?.bookingStartTime) {
          <p class="start-time">
            Početak:
            {{ checkInService.currentStatus()?.bookingStartTime | date : 'dd.MM.yyyy HH:mm' }}
          </p>
          }
        </div>
        } }
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
        background: var(--toolbar-bg, linear-gradient(135deg, #1976d2, #6366f1));
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
        background: var(--primary-color, #1976d2);
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
        color: var(--primary-color, #1976d2);
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
        color: var(--primary-color, #1976d2);
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
        color: var(--primary-color, #1976d2) !important;
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

  checkInService = inject(CheckInService);
  geolocationService = inject(GeolocationService);
  offlineQueueService = inject(OfflineQueueService);

  // Booking ID from route
  bookingId = signal<number>(0);

  // Review mode: Host can review their submitted data in read-only mode
  showingReview = signal(false);

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
    const phase = this.checkInService.currentPhase();
    return phase === 'HOST_PHASE' || phase === 'GUEST_PHASE';
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
    this.checkInService.loadStatus(this.bookingId()).subscribe({
      next: (status) => {
        console.log('[CheckInWizard] Status refreshed:', status.status);
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
      }
    );
  }

  retryLocation(): void {
    this.geolocationService.getCurrentPosition().then(
      () => this.geolocationService.startWatching(),
      (err) => console.warn('[CheckInWizard] Retry GPS error:', err)
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
}
