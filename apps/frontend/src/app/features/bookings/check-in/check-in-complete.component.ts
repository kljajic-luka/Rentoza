/**
 * Check-In Complete Component
 *
 * Success screen displayed after handshake is confirmed.
 * Shows trip details and next steps.
 */
import { Component, Input, inject, computed, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatDividerModule } from '@angular/material/divider';

import { CheckInStatusDTO } from '../../../core/models/check-in.model';

@Component({
  selector: 'app-check-in-complete',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatDividerModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="complete-screen">
      <!-- Success animation -->
      <div class="success-icon">
        <mat-icon>celebration</mat-icon>
      </div>

      <h1>Check-in završen!</h1>
      <p class="subtitle">Srećan put! 🚗</p>

      <!-- Trip details card -->
      <mat-card class="trip-card">
        <mat-card-content>
          <!-- Vehicle info -->
          @if (status?.car) {
          <div class="vehicle-info">
            @if (status?.car?.imageUrl) {
            <img [src]="status!.car.imageUrl" [alt]="vehicleTitle()" class="vehicle-thumb" />
            }
            <div>
              <h3>{{ vehicleTitle() }}</h3>
              <p class="vehicle-details">
                {{ status?.odometerReading | number }} km • {{ status?.fuelLevelPercent }}% gorivo
              </p>
            </div>
          </div>
          }

          <mat-divider></mat-divider>

          <!-- Timestamps -->
          <div class="timestamps">
            <div class="timestamp-item">
              <mat-icon>login</mat-icon>
              <div>
                <span class="label">Check-in</span>
                <span class="value">{{
                  status?.handshakeCompletedAt | date : 'dd.MM.yyyy HH:mm'
                }}</span>
              </div>
            </div>
            <div class="timestamp-item">
              <mat-icon>logout</mat-icon>
              <div>
                <span class="label">Check-out do</span>
                <span class="value">{{ checkoutDeadline() | date : 'dd.MM.yyyy HH:mm' }}</span>
              </div>
            </div>
          </div>
        </mat-card-content>
      </mat-card>

      <!-- Next steps -->
      <mat-card class="tips-card">
        <mat-card-content>
          <h4>
            <mat-icon>tips_and_updates</mat-icon>
            Saveti za putovanje
          </h4>
          <ul>
            @if (status?.guest) {
            <li>Fotografišite vozilo pre polaska</li>
            <li>Proverite gorivo i dokumente u vozilu</li>
            <li>U slučaju problema kontaktirajte domaćina</li>
            } @else {
            <li>Ostanite dostupni za pitanja gosta</li>
            <li>Pratite status rezervacije u aplikaciji</li>
            <li>Pripremite se za check-out</li>
            }
          </ul>
        </mat-card-content>
      </mat-card>

      <!-- Actions -->
      <div class="actions">
        <button
          mat-raised-button
          color="primary"
          [routerLink]="['/bookings', bookingId]"
          class="primary-action"
        >
          <mat-icon>arrow_back</mat-icon>
          Nazad na rezervaciju
        </button>

        @if (status?.guest) {
        <button mat-stroked-button [routerLink]="['/support']">
          <mat-icon>support_agent</mat-icon>
          Podrška
        </button>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .complete-screen {
        padding: 24px 16px;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 20px;
        text-align: center;
      }

      /* Success icon */
      .success-icon {
        width: 100px;
        height: 100px;
        border-radius: 50%;
        background: linear-gradient(135deg, #4caf50 0%, #81c784 100%);
        display: flex;
        align-items: center;
        justify-content: center;
        animation: pop 0.5s ease-out;
      }

      .success-icon mat-icon {
        font-size: 56px;
        width: 56px;
        height: 56px;
        color: white;
      }

      @keyframes pop {
        0% {
          transform: scale(0);
        }
        50% {
          transform: scale(1.2);
        }
        100% {
          transform: scale(1);
        }
      }

      h1 {
        margin: 0;
        font-size: 28px;
        color: var(--success-color, #4caf50);
      }

      .subtitle {
        margin: 0;
        font-size: 18px;
        color: var(--color-text-muted, #757575);
      }

      /* Trip card */
      .trip-card {
        width: 100%;
        background: var(--color-surface, #ffffff);
      }

      .vehicle-info {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 16px;
      }

      .vehicle-thumb {
        width: 60px;
        height: 60px;
        border-radius: 8px;
        object-fit: cover;
      }

      .vehicle-info h3 {
        margin: 0;
        font-size: 16px;
        color: var(--color-text-primary, #212121);
      }

      .vehicle-details {
        margin: 4px 0 0;
        font-size: 13px;
        color: var(--color-text-muted, #757575);
      }

      mat-divider {
        margin: 16px 0;
      }

      .timestamps {
        display: flex;
        justify-content: space-around;
      }

      .timestamp-item {
        display: flex;
        align-items: center;
        gap: 8px;
      }

      .timestamp-item mat-icon {
        color: var(--primary-color, #1976d2);
      }

      .timestamp-item .label {
        display: block;
        font-size: 12px;
        color: var(--color-text-muted, #757575);
      }

      .timestamp-item .value {
        display: block;
        font-size: 14px;
        font-weight: 500;
        color: var(--color-text-primary, #212121);
      }

      /* Tips card */
      .tips-card {
        width: 100%;
        background: var(--color-surface-muted, #e3f2fd);
      }

      .tips-card h4 {
        display: flex;
        align-items: center;
        gap: 8px;
        margin: 0 0 12px;
        color: var(--info-color, #1565c0);
      }

      .tips-card ul {
        margin: 0;
        padding-left: 20px;
      }

      .tips-card li {
        margin: 8px 0;
        font-size: 14px;
        color: var(--color-text-primary, #212121);
      }

      /* Actions */
      .actions {
        display: flex;
        flex-direction: column;
        gap: 12px;
        width: 100%;
        margin-top: 8px;
      }

      .primary-action {
        height: 48px;
      }
    `,
  ],
})
export class CheckInCompleteComponent {
  @Input() bookingId!: number;
  @Input() status!: CheckInStatusDTO | null;

  private router = inject(Router);

  vehicleTitle = computed(() => {
    const car = this.status?.car;
    if (!car) return 'Vozilo';
    return `${car.brand} ${car.model} (${car.year})`;
  });

  checkoutDeadline = computed(() => {
    // Trip end time from status
    if (this.status?.tripStartScheduled) {
      // Assuming tripStartScheduled is the booking start,
      // we'd need booking end time from backend
      // For now, return a placeholder
      return this.status.tripStartScheduled;
    }
    return null;
  });
}
