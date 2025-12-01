/**
 * Handshake Component
 *
 * Final confirmation step where both host and guest confirm the handoff.
 * Features:
 * - Swipe-to-confirm gesture for physical handoff
 * - Optional physical ID verification (host verifies guest's ID)
 * - Digital signatures captured
 * - **Geofence Distance Indicator** (Phase 2: Trust UI)
 */
import {
  Component,
  Input,
  Output,
  EventEmitter,
  inject,
  signal,
  computed,
  ElementRef,
  ViewChild,
  ChangeDetectionStrategy,
  OnInit,
  OnDestroy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { CheckInService } from '../../../core/services/check-in.service';
import { GeolocationService } from '../../../core/services/geolocation.service';
import { CheckInStatusDTO } from '../../../core/models/check-in.model';

@Component({
  selector: 'app-handshake',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatCheckboxModule,
    MatDividerModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="handshake">
      <!-- Header -->
      <div class="handshake-header">
        <mat-icon class="handshake-icon">handshake</mat-icon>
        <h2>Potvrda primopredaje</h2>
        <p>{{ roleInstructions() }}</p>
      </div>

      <!-- Geofence Distance Badge -->
      @if (status?.geofenceDistanceMeters !== null && status?.geofenceDistanceMeters !== undefined)
      {
      <div
        class="geofence-badge"
        [class.close]="isWithinGeofence()"
        [class.far]="!isWithinGeofence()"
      >
        <mat-icon>{{ isWithinGeofence() ? 'location_on' : 'location_off' }}</mat-icon>
        <span class="distance-text">
          Udaljenost do vozila: {{ formatDistance(status!.geofenceDistanceMeters!) }}
        </span>
        @if (isWithinGeofence()) {
        <mat-icon class="status-icon">check_circle</mat-icon>
        } @else {
        <mat-icon class="status-icon warning-icon">warning</mat-icon>
        }
      </div>
      }

      <!-- Status indicators -->
      <mat-card class="status-card">
        <mat-card-content>
          <div class="status-item" [class.completed]="status?.hostCheckInComplete">
            <mat-icon>{{ status?.hostCheckInComplete ? 'check_circle' : 'pending' }}</mat-icon>
            <div>
              <span class="status-label">Domaćin</span>
              <span class="status-text">{{
                status?.hostCheckInComplete ? 'Završio' : 'Čeka potvrdu'
              }}</span>
            </div>
          </div>

          <mat-divider vertical></mat-divider>

          <div class="status-item" [class.completed]="status?.guestCheckInComplete">
            <mat-icon>{{ status?.guestCheckInComplete ? 'check_circle' : 'pending' }}</mat-icon>
            <div>
              <span class="status-label">Gost</span>
              <span class="status-text">{{
                status?.guestCheckInComplete ? 'Potvrdio' : 'Čeka potvrdu'
              }}</span>
            </div>
          </div>
        </mat-card-content>
      </mat-card>

      <!-- Host: ID verification option -->
      @if (status?.host) {
      <mat-card class="id-verification-card">
        <mat-card-content>
          <mat-checkbox [(ngModel)]="verifyPhysicalId" color="primary">
            <div class="checkbox-content">
              <span class="checkbox-label">Verifikovao sam identitet gosta</span>
              <span class="checkbox-hint">Proverio sam ličnu kartu ili vozačku dozvolu</span>
            </div>
          </mat-checkbox>
        </mat-card-content>
      </mat-card>
      }

      <!-- Swipe to confirm -->
      <div
        class="swipe-container"
        (touchstart)="onTouchStart($event)"
        (touchmove)="onTouchMove($event)"
        (touchend)="onTouchEnd()"
        (mousedown)="onMouseDown($event)"
      >
        <div class="swipe-track">
          <div
            class="swipe-thumb"
            [style.transform]="'translateX(' + swipeProgress() + 'px)'"
            [class.active]="isSwiping()"
            [class.confirmed]="isConfirmed()"
          >
            @if (isConfirmed()) {
            <mat-icon>check</mat-icon>
            } @else {
            <mat-icon>arrow_forward</mat-icon>
            }
          </div>

          <div class="swipe-text" [class.hidden]="swipeProgress() > 50">
            {{ isConfirmed() ? 'Potvrđeno!' : 'Prevucite za potvrdu →' }}
          </div>

          <div class="swipe-bg" [style.width.px]="swipeProgress() + 48"></div>
        </div>
      </div>

      <!-- Waiting message -->
      @if (isWaitingForOther()) {
      <div class="waiting-message">
        <mat-progress-spinner mode="indeterminate" diameter="24"></mat-progress-spinner>
        <span>{{ waitingMessage() }}</span>
      </div>
      }

      <!-- Alternative button (for accessibility) -->
      <button
        mat-stroked-button
        color="primary"
        [disabled]="!canConfirm() || checkInService.isLoading()"
        (click)="confirmViaButton()"
        class="alternative-button"
      >
        Alternativno: Potvrdi dodirom
      </button>
    </div>
  `,
  styles: [
    `
      .handshake {
        padding: 24px 16px;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 24px;
      }

      /* Header */
      .handshake-header {
        text-align: center;
      }

      .handshake-icon {
        font-size: 64px;
        width: 64px;
        height: 64px;
        color: var(--primary-color, #1976d2);
        margin-bottom: 16px;
      }

      .handshake-header h2 {
        margin: 0 0 8px;
        font-size: 24px;
        color: var(--color-text-primary, #212121);
      }

      .handshake-header p {
        margin: 0;
        color: var(--color-text-muted, #757575);
        max-width: 280px;
      }

      /* Status card */
      .status-card {
        width: 100%;
        background: var(--color-surface, #ffffff);
      }

      .status-card mat-card-content {
        display: flex;
        justify-content: space-around;
        align-items: center;
        padding: 16px !important;
      }

      .status-item {
        display: flex;
        align-items: center;
        gap: 8px;
      }

      .status-item mat-icon {
        font-size: 32px;
        width: 32px;
        height: 32px;
        color: var(--color-text-muted, #bdbdbd);
      }

      .status-item.completed mat-icon {
        color: var(--success-color, #4caf50);
      }

      .status-label {
        display: block;
        font-weight: 500;
        font-size: 14px;
        color: var(--color-text-primary, #212121);
      }

      .status-text {
        display: block;
        font-size: 12px;
        color: var(--color-text-muted, #757575);
      }

      /* ID verification */
      .id-verification-card {
        width: 100%;
        background: var(--color-surface, #ffffff);
      }

      .checkbox-content {
        display: flex;
        flex-direction: column;
      }

      .checkbox-label {
        font-weight: 500;
        color: var(--color-text-primary, #212121);
      }

      .checkbox-hint {
        font-size: 12px;
        color: var(--color-text-muted, #757575);
      }

      /* Swipe to confirm */
      .swipe-container {
        width: 100%;
        touch-action: pan-y;
        user-select: none;
      }

      .swipe-track {
        position: relative;
        height: 56px;
        background: var(--color-border-subtle, #e0e0e0);
        border-radius: 28px;
        overflow: hidden;
      }

      .swipe-bg {
        position: absolute;
        top: 0;
        left: 0;
        height: 100%;
        background: var(--primary-color, #1976d2);
        border-radius: 28px;
        transition: width 0.1s ease;
      }

      .swipe-thumb {
        position: absolute;
        top: 4px;
        left: 4px;
        width: 48px;
        height: 48px;
        background: var(--color-surface, #ffffff);
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.2);
        z-index: 10;
        transition: transform 0.1s ease;
        color: var(--color-text-primary, #212121);
      }

      .swipe-thumb.active {
        transform: scale(1.1);
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
      }

      .swipe-thumb.confirmed {
        background: var(--success-color, #4caf50);
        color: white;
      }

      .swipe-thumb mat-icon {
        font-size: 24px;
      }

      .swipe-text {
        position: absolute;
        top: 50%;
        left: 50%;
        transform: translate(-50%, -50%);
        color: var(--color-text-muted, #757575);
        font-weight: 500;
        white-space: nowrap;
        transition: opacity 0.2s ease;
        z-index: 5;
      }

      .swipe-text.hidden {
        opacity: 0;
      }

      /* Waiting message */
      .waiting-message {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 16px 24px;
        background: var(--color-surface-muted, #e3f2fd);
        border-radius: 12px;
        color: var(--info-color, #1565c0);
      }

      /* Alternative button */
      .alternative-button {
        margin-top: 8px;
        font-size: 12px;
      }

      /* Geofence Distance Badge */
      .geofence-badge {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 12px 16px;
        border-radius: 12px;
        font-size: 14px;
        font-weight: 500;
        width: 100%;
        box-sizing: border-box;
      }

      .geofence-badge.close {
        background: rgba(34, 197, 94, 0.15);
        color: #166534;
        border: 1px solid rgba(34, 197, 94, 0.3);
      }

      .geofence-badge.far {
        background: rgba(239, 68, 68, 0.15);
        color: #b91c1c;
        border: 1px solid rgba(239, 68, 68, 0.3);
      }

      .geofence-badge mat-icon {
        font-size: 20px;
        width: 20px;
        height: 20px;
      }

      .geofence-badge .distance-text {
        flex: 1;
      }

      .geofence-badge .status-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }

      .geofence-badge.close .status-icon {
        color: #16a34a;
      }

      .geofence-badge .warning-icon {
        color: #dc2626;
        animation: pulse-warning 1.5s ease-in-out infinite;
      }

      @keyframes pulse-warning {
        0%,
        100% {
          opacity: 1;
        }
        50% {
          opacity: 0.5;
        }
      }

      /* Dark theme adjustments */
      :host-context(.theme-dark) .geofence-badge.close {
        background: rgba(34, 197, 94, 0.2);
        color: #86efac;
      }

      :host-context(.theme-dark) .geofence-badge.far {
        background: rgba(239, 68, 68, 0.2);
        color: #fca5a5;
      }
    `,
  ],
})
export class HandshakeComponent implements OnInit, OnDestroy {
  @Input() bookingId!: number;
  @Input() status!: CheckInStatusDTO | null;
  @Output() completed = new EventEmitter<void>();

  @ViewChild('swipeContainer') swipeContainer!: ElementRef;

  private snackBar = inject(MatSnackBar);
  checkInService = inject(CheckInService);
  private geolocationService = inject(GeolocationService);

  // Geofence threshold in meters (100m = close, >100m = far)
  private readonly GEOFENCE_THRESHOLD_METERS = 100;

  // State
  verifyPhysicalId = false;
  private _swipeProgress = signal(0);
  private _isSwiping = signal(false);
  private _isConfirmed = signal(false);
  private startX = 0;
  private maxSwipe = 0;

  swipeProgress = this._swipeProgress.asReadonly();
  isSwiping = this._isSwiping.asReadonly();
  isConfirmed = this._isConfirmed.asReadonly();

  // Computed
  roleInstructions = computed(() => {
    if (this.status?.host) {
      return 'Kada predate ključeve gostu, prevucite za potvrdu';
    }
    return 'Kada primite ključeve, prevucite za potvrdu';
  });

  isWaitingForOther = computed(() => {
    if (!this.status) return false;
    if (this.status.host) {
      return this._isConfirmed() && !this.status.guestCheckInComplete;
    }
    return this._isConfirmed() && !this.status.hostCheckInComplete;
  });

  waitingMessage = computed(() => {
    if (this.status?.host) {
      return 'Čekanje potvrde gosta...';
    }
    return 'Čekanje potvrde domaćina...';
  });

  canConfirm = computed(() => {
    return !this._isConfirmed() && !this.checkInService.isLoading();
  });

  /**
   * Check if user is within geofence threshold (close to vehicle).
   * Green if < 100m, Red if >= 100m.
   */
  isWithinGeofence(): boolean {
    const distance = this.status?.geofenceDistanceMeters;
    if (distance === null || distance === undefined) return true; // Assume OK if no data
    return distance < this.GEOFENCE_THRESHOLD_METERS;
  }

  /**
   * Format distance for display.
   * Shows meters if < 1000m, otherwise km.
   */
  formatDistance(meters: number): string {
    if (meters < 1000) {
      return `${Math.round(meters)}m`;
    }
    return `${(meters / 1000).toFixed(1)}km`;
  }

  // Touch handlers
  onTouchStart(event: TouchEvent): void {
    if (!this.canConfirm()) return;

    this.startX = event.touches[0].clientX;
    this._isSwiping.set(true);
    this.calculateMaxSwipe();
  }

  onTouchMove(event: TouchEvent): void {
    if (!this._isSwiping()) return;

    const currentX = event.touches[0].clientX;
    const diff = currentX - this.startX;
    const progress = Math.max(0, Math.min(diff, this.maxSwipe));
    this._swipeProgress.set(progress);

    // Prevent page scroll while swiping
    if (diff > 10) {
      event.preventDefault();
    }
  }

  onTouchEnd(): void {
    this.finalizeSwipe();
  }

  // Mouse handlers (for desktop testing)
  onMouseDown(event: MouseEvent): void {
    if (!this.canConfirm()) return;

    this.startX = event.clientX;
    this._isSwiping.set(true);
    this.calculateMaxSwipe();

    const onMouseMove = (e: MouseEvent) => {
      const diff = e.clientX - this.startX;
      const progress = Math.max(0, Math.min(diff, this.maxSwipe));
      this._swipeProgress.set(progress);
    };

    const onMouseUp = () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
      this.finalizeSwipe();
    };

    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
  }

  private calculateMaxSwipe(): void {
    // Track width minus thumb width and padding
    this.maxSwipe = window.innerWidth - 32 - 48 - 8; // container padding, thumb, extra padding
  }

  private finalizeSwipe(): void {
    this._isSwiping.set(false);

    const threshold = this.maxSwipe * 0.7;

    if (this._swipeProgress() >= threshold) {
      // Confirm
      this._swipeProgress.set(this.maxSwipe);
      this.confirmHandshake();
    } else {
      // Reset
      this._swipeProgress.set(0);
    }
  }

  confirmViaButton(): void {
    if (!this.canConfirm()) return;
    this.confirmHandshake();
  }

  private confirmHandshake(): void {
    this._isConfirmed.set(true);

    this.checkInService
      .confirmHandshake(this.bookingId, this.status?.host ? this.verifyPhysicalId : undefined)
      .subscribe({
        next: (status) => {
          if (status.handshakeCompletedAt) {
            this.snackBar.open('Primopredaja uspešno završena! 🎉', 'Odlično', {
              duration: 5000,
              panelClass: 'success-snackbar',
            });
            this.completed.emit();
          } else {
            // Other party hasn't confirmed yet
            this.snackBar.open('Vaša potvrda je zabeležena. Čekamo drugu stranu.', '', {
              duration: 3000,
            });
          }
        },
        error: (err) => {
          this._isConfirmed.set(false);
          this._swipeProgress.set(0);
          const message = err.error?.message || 'Potvrda nije uspela. Pokušajte ponovo.';
          this.snackBar.open(message, 'OK', { duration: 5000 });
        },
      });
  }

  ngOnInit(): void {
    // Start polling for other party's confirmation
    if (this.bookingId) {
      this.checkInService.startPolling(this.bookingId, 5000);
    }
  }

  ngOnDestroy(): void {
    this.checkInService.stopPolling();
  }
}
