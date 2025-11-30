/**
 * Check-In Waiting Component
 *
 * A holding screen displayed when one party (Host or Guest)
 * has completed their task and is waiting for the other party.
 *
 * Use cases:
 * - Guest waiting for Host to complete photos/form (HOST_PHASE)
 * - Host waiting for Guest to review and acknowledge (GUEST_PHASE)
 */
import {
  Component,
  Input,
  Output,
  EventEmitter,
  ChangeDetectionStrategy,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';

import { CheckInStatusDTO } from '../../../core/models/check-in.model';

@Component({
  selector: 'app-check-in-waiting',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    MatIconModule,
    MatButtonModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatDividerModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="waiting-screen">
      <!-- Main status icon with animation -->
      <div class="status-icon" [class.pulse]="animate" [class.success]="iconType === 'success'">
        <mat-icon>{{ icon }}</mat-icon>
      </div>

      <!-- Title and message -->
      <h2 class="title">{{ title }}</h2>
      <p class="message">{{ message }}</p>

      <!-- Vehicle info card -->
      @if (status?.car) {
      <mat-card class="vehicle-card">
        <mat-card-header>
          <mat-icon mat-card-avatar>directions_car</mat-icon>
          <mat-card-title>{{ status!.car.brand }} {{ status!.car.model }}</mat-card-title>
          <mat-card-subtitle>{{ status!.car.year }}</mat-card-subtitle>
        </mat-card-header>
      </mat-card>
      }

      <!-- Submitted data summary (for host after submission) -->
      @if (showSubmittedData && status?.odometerReading !== null) {
      <mat-card class="summary-card">
        <mat-card-header>
          <mat-icon mat-card-avatar>assignment_turned_in</mat-icon>
          <mat-card-title>Vaši uneti podaci</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="data-row">
            <span class="label">Kilometraža:</span>
            <span class="value">{{ status?.odometerReading | number }} km</span>
          </div>
          <div class="data-row">
            <span class="label">Nivo goriva:</span>
            <span class="value">{{ status?.fuelLevelPercent }}%</span>
          </div>
          <div class="data-row">
            <span class="label">Fotografije:</span>
            <span class="value">{{ status?.vehiclePhotos?.length || 0 }} uploadovano</span>
          </div>
        </mat-card-content>
      </mat-card>
      }

      <!-- Next steps timeline -->
      @if (nextSteps.length > 0) {
      <div class="next-steps">
        <h4>Sledeći koraci</h4>
        <ol class="steps-list">
          @for (step of nextSteps; track step; let i = $index) {
          <li [class.current]="i === 0">
            <span class="step-number">{{ i + 1 }}</span>
            <span class="step-text">{{ step }}</span>
          </li>
          }
        </ol>
      </div>
      }

      <!-- Action buttons -->
      <div class="actions">
        @if (showReviewButton) {
        <button mat-stroked-button color="primary" (click)="reviewData.emit()">
          <mat-icon>visibility</mat-icon>
          Pregledaj unete podatke
        </button>
        }

        <button mat-stroked-button (click)="onRefresh()" [disabled]="isRefreshing()">
          @if (isRefreshing()) {
          <mat-spinner diameter="18"></mat-spinner>
          } @else {
          <mat-icon>refresh</mat-icon>
          } Osveži status
        </button>

        <button
          mat-raised-button
          color="accent"
          [routerLink]="status?.host ? ['/owner/bookings'] : ['/bookings', status?.bookingId]"
        >
          <mat-icon>arrow_back</mat-icon>
          {{ status?.host ? 'Nazad na rezervacije' : 'Nazad na rezervaciju' }}
        </button>
      </div>

      <!-- Auto-refresh hint -->
      <p class="auto-refresh-hint">
        <mat-icon>info</mat-icon>
        Status se automatski osvežava svakih 30 sekundi.
      </p>
    </div>
  `,
  styles: [
    `
      .waiting-screen {
        display: flex;
        flex-direction: column;
        align-items: center;
        padding: 24px 16px;
        text-align: center;
        min-height: 60vh;
      }

      /* Status icon */
      .status-icon {
        width: 96px;
        height: 96px;
        border-radius: 50%;
        background: var(--primary-light, rgba(25, 118, 210, 0.12));
        display: flex;
        align-items: center;
        justify-content: center;
        margin-bottom: 24px;
      }

      .status-icon mat-icon {
        font-size: 48px;
        width: 48px;
        height: 48px;
        color: var(--primary-color, #1976d2);
      }

      .status-icon.success {
        background: var(--success-light, rgba(76, 175, 80, 0.12));
      }

      .status-icon.success mat-icon {
        color: var(--success-color, #4caf50);
      }

      .status-icon.pulse mat-icon {
        animation: pulse 2s ease-in-out infinite;
      }

      @keyframes pulse {
        0%,
        100% {
          opacity: 1;
          transform: scale(1);
        }
        50% {
          opacity: 0.7;
          transform: scale(0.95);
        }
      }

      /* Title and message */
      .title {
        margin: 0 0 8px;
        font-size: 22px;
        font-weight: 500;
        color: var(--color-text-primary, #212121);
      }

      .message {
        margin: 0 0 24px;
        font-size: 15px;
        color: var(--color-text-muted, #757575);
        max-width: 320px;
        line-height: 1.5;
      }

      /* Vehicle card */
      .vehicle-card {
        width: 100%;
        max-width: 360px;
        margin-bottom: 20px;
      }

      .vehicle-card mat-card-header {
        padding: 12px 16px;
      }

      .vehicle-card [mat-card-avatar] {
        background: var(--primary-light, rgba(25, 118, 210, 0.12));
        color: var(--primary-color, #1976d2);
        display: flex;
        align-items: center;
        justify-content: center;
        border-radius: 50%;
        width: 40px;
        height: 40px;
      }

      /* Summary card */
      .summary-card {
        width: 100%;
        max-width: 360px;
        margin-bottom: 20px;
      }

      .summary-card mat-card-header {
        padding: 12px 16px 8px;
      }

      .summary-card [mat-card-avatar] {
        background: var(--success-light, rgba(76, 175, 80, 0.12));
        color: var(--success-color, #4caf50);
        display: flex;
        align-items: center;
        justify-content: center;
        border-radius: 50%;
        width: 40px;
        height: 40px;
      }

      .summary-card mat-card-content {
        padding: 0 16px 16px;
      }

      .data-row {
        display: flex;
        justify-content: space-between;
        padding: 8px 0;
        border-bottom: 1px solid var(--color-border-subtle, #eee);
      }

      .data-row:last-child {
        border-bottom: none;
      }

      .data-row .label {
        color: var(--color-text-muted, #757575);
        font-size: 14px;
      }

      .data-row .value {
        color: var(--color-text-primary, #212121);
        font-weight: 500;
        font-size: 14px;
      }

      /* Next steps */
      .next-steps {
        width: 100%;
        max-width: 360px;
        text-align: left;
        margin-bottom: 24px;
        padding: 16px;
        background: var(--color-surface-muted, #fafafa);
        border-radius: 12px;
      }

      .next-steps h4 {
        margin: 0 0 12px;
        font-size: 14px;
        font-weight: 500;
        color: var(--color-text-muted, #757575);
        text-transform: uppercase;
        letter-spacing: 0.5px;
      }

      .steps-list {
        list-style: none;
        padding: 0;
        margin: 0;
      }

      .steps-list li {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 10px 0;
        color: var(--color-text-muted, #757575);
        font-size: 14px;
      }

      .steps-list li.current {
        color: var(--primary-color, #1976d2);
        font-weight: 500;
      }

      .step-number {
        width: 24px;
        height: 24px;
        border-radius: 50%;
        background: var(--color-surface-muted, #e0e0e0);
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 12px;
        font-weight: 600;
        flex-shrink: 0;
      }

      .steps-list li.current .step-number {
        background: var(--primary-color, #1976d2);
        color: white;
      }

      /* Actions */
      .actions {
        display: flex;
        flex-direction: column;
        gap: 12px;
        width: 100%;
        max-width: 320px;
        margin-bottom: 20px;
      }

      .actions button {
        width: 100%;
        height: 44px;
      }

      .actions button mat-icon {
        margin-right: 8px;
      }

      .actions button mat-spinner {
        margin-right: 8px;
      }

      /* Auto-refresh hint */
      .auto-refresh-hint {
        display: flex;
        align-items: center;
        gap: 6px;
        font-size: 12px;
        color: var(--color-text-muted, #9e9e9e);
        margin: 0;
      }

      .auto-refresh-hint mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
      }
    `,
  ],
})
export class CheckInWaitingComponent {
  @Input() status: CheckInStatusDTO | null = null;
  @Input() title = 'Čekanje...';
  @Input() message = '';
  @Input() icon = 'hourglass_empty';
  @Input() iconType: 'waiting' | 'success' = 'waiting';
  @Input() nextSteps: string[] = [];
  @Input() animate = true;
  @Input() showReviewButton = false;
  @Input() showSubmittedData = false;

  @Output() refresh = new EventEmitter<void>();
  @Output() reviewData = new EventEmitter<void>();

  // Internal state for refresh button
  isRefreshing = signal(false);

  onRefresh(): void {
    this.isRefreshing.set(true);
    this.refresh.emit();

    // Reset after delay (parent will update status)
    setTimeout(() => {
      this.isRefreshing.set(false);
    }, 2000);
  }
}
