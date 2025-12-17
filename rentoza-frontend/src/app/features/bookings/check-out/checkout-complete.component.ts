/**
 * Checkout Complete Component
 *
 * Displays a success screen after checkout is completed.
 * Shows trip summary, damage status (if any), and next actions.
 */
import {
  Component,
  Input,
  inject,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';

import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatDividerModule } from '@angular/material/divider';

import { CheckOutStatusDTO, DAMAGE_CLAIM_STATUS_LABELS, DamageClaimStatus } from '@core/models/checkout.model';

@Component({
  selector: 'app-checkout-complete',
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
    <div class="checkout-complete">
      <mat-card class="success-card">
        <div class="success-icon">
          <mat-icon>check_circle</mat-icon>
        </div>

        <h2>Checkout završen!</h2>
        <p class="subtitle">
          @if (status?.isHost) {
            Vozilo je uspešno vraćeno.
          } @else {
            Hvala na korišćenju Rentoza!
          }
        </p>

        <!-- Trip Summary -->
        <div class="trip-summary">
          <h3>Pregled putovanja</h3>
          
          <div class="summary-grid">
            <div class="summary-item">
              <mat-icon>speed</mat-icon>
              <div class="details">
                <span class="label">Pređeno</span>
                <strong>{{ status?.totalMileage || 0 }} km</strong>
              </div>
            </div>

            <div class="summary-item">
              <mat-icon>local_gas_station</mat-icon>
              <div class="details">
                <span class="label">Gorivo</span>
                <strong [class.negative]="(status?.fuelDifference ?? 0) < 0">
                  {{ status?.fuelDifference ?? 0 }}%
                </strong>
              </div>
            </div>

            @if (status?.tripStartedAt && status?.actualReturnTime) {
              <div class="summary-item">
                <mat-icon>schedule</mat-icon>
                <div class="details">
                  <span class="label">Trajanje</span>
                  <strong>{{ getTripDuration() }}</strong>
                </div>
              </div>
            }

            @if (status && status.lateReturnMinutes && status.lateReturnMinutes > 0) {
              <div class="summary-item late">
                <mat-icon>warning</mat-icon>
                <div class="details">
                  <span class="label">Kašnjenje</span>
                  <strong>{{ status.lateReturnMinutes }} min</strong>
                </div>
              </div>
            }
          </div>
        </div>

        <mat-divider></mat-divider>

        <!-- Damage Report (if any) -->
        @if (status?.newDamageReported) {
          <div class="damage-report">
            <h3>
              <mat-icon color="warn">report_problem</mat-icon>
              Prijavljeno oštećenje
            </h3>
            
            @if (status?.damageDescription) {
              <p class="damage-desc">{{ status!.damageDescription }}</p>
            }
            
            <div class="damage-details">
              @if (status?.damageClaimAmount) {
                <div class="detail-row">
                  <span>Procenjena šteta:</span>
                  <strong>{{ status!.damageClaimAmount | number:'1.0-0' }} RSD</strong>
                </div>
              }
              @if (status && status.damageClaimStatus) {
                <div class="detail-row">
                  <span>Status:</span>
                  <span class="status-badge" [class]="status.damageClaimStatus.toLowerCase()">
                    {{ getDamageStatusLabel(status.damageClaimStatus) }}
                  </span>
                </div>
              }
            </div>

            <p class="damage-note">
              @if (status?.isGuest) {
                Kontaktiraćemo vas u vezi sa ovim oštećenjem.
              } @else {
                Gost je obavešten. Pratite status u rezervacijama.
              }
            </p>
          </div>

          <mat-divider></mat-divider>
        }

        <!-- Late Fee (if any) -->
        @if (status && status.lateFeeAmount && status.lateFeeAmount > 0) {
          <div class="late-fee">
            <mat-icon>payment</mat-icon>
            <div>
              <strong>Naknada za kašnjenje</strong>
              <span>{{ status.lateFeeAmount | number:'1.0-0' }} RSD</span>
            </div>
          </div>

          <mat-divider></mat-divider>
        }

        <!-- Next Steps -->
        <div class="next-steps">
          <h3>Šta dalje?</h3>
          @if (status?.isGuest) {
            <ul>
              <li>Ostavite recenziju o vozilu i domaćinu</li>
              <li>Proverite transakcije u istoriji</li>
              <li>Rezervišite sledeće putovanje</li>
            </ul>
          } @else {
            <ul>
              <li>Ostavite recenziju o gostu</li>
              <li>Proverite stanje vozila</li>
              <li>Vaš prihod biće isplaćen u roku od 3-5 radnih dana</li>
            </ul>
          }
        </div>

        <!-- Actions -->
        <div class="actions">
          <button mat-raised-button color="primary" (click)="goToBookings()">
            <mat-icon>list</mat-icon>
            Moje rezervacije
          </button>
          @if (status?.isGuest) {
            <button mat-stroked-button (click)="goToSearch()">
              <mat-icon>search</mat-icon>
              Nova rezervacija
            </button>
          }
        </div>
      </mat-card>
    </div>
  `,
  styles: [`
    .checkout-complete {
      display: flex;
      justify-content: center;
      padding: 16px;
    }

    .success-card {
      max-width: 600px;
      text-align: center;
      padding: 32px 24px;
    }

    .success-icon {
      margin-bottom: 16px;

      mat-icon {
        font-size: 80px;
        width: 80px;
        height: 80px;
        color: var(--success-color, #4caf50);
      }
    }

    h2 {
      margin: 0;
      font-size: 1.75rem;
    }

    .subtitle {
      color: var(--text-secondary);
      margin-bottom: 24px;
    }

    .trip-summary {
      margin: 24px 0;

      h3 {
        margin: 0 0 16px;
        font-size: 1rem;
        font-weight: 500;
      }
    }

    .summary-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
      gap: 16px;
    }

    .summary-item {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 16px;
      background: var(--surface-color);
      border-radius: 12px;

      mat-icon {
        color: var(--primary-color);
      }

      .details {
        display: flex;
        flex-direction: column;
        text-align: left;

        .label {
          font-size: 0.75rem;
          color: var(--text-secondary);
        }

        .negative {
          color: var(--warn-color);
        }
      }

      &.late {
        background: var(--warn-color-light, #fff3e0);

        mat-icon {
          color: var(--warn-color);
        }
      }
    }

    mat-divider {
      margin: 24px 0;
    }

    .damage-report {
      text-align: left;
      padding: 16px;
      background: var(--warn-color-light, #fff3e0);
      border-radius: 8px;

      h3 {
        display: flex;
        align-items: center;
        gap: 8px;
        margin: 0 0 12px;
        font-size: 1rem;
      }

      .damage-desc {
        margin: 0 0 16px;
        color: var(--text-secondary);
      }

      .damage-details {
        .detail-row {
          display: flex;
          justify-content: space-between;
          padding: 8px 0;
          border-bottom: 1px solid rgba(0,0,0,0.1);

          &:last-child {
            border-bottom: none;
          }
        }
      }

      .status-badge {
        padding: 4px 8px;
        border-radius: 4px;
        font-size: 0.75rem;
        font-weight: 500;

        &.pending {
          background: #fff3e0;
          color: #e65100;
        }
        &.approved {
          background: #e3f2fd;
          color: #1565c0;
        }
        &.rejected {
          background: #ffebee;
          color: #c62828;
        }
        &.paid {
          background: #e8f5e9;
          color: #2e7d32;
        }
      }

      .damage-note {
        margin: 16px 0 0;
        font-size: 0.875rem;
        color: var(--text-secondary);
      }
    }

    .late-fee {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 16px;
      background: var(--warn-color-light, #fff3e0);
      border-radius: 8px;

      mat-icon {
        color: var(--warn-color);
      }

      div {
        display: flex;
        flex-direction: column;
        text-align: left;
      }
    }

    .next-steps {
      text-align: left;
      margin-bottom: 24px;

      h3 {
        margin: 0 0 12px;
        font-size: 1rem;
      }

      ul {
        margin: 0;
        padding-left: 20px;

        li {
          margin-bottom: 8px;
          color: var(--text-secondary);
        }
      }
    }

    .actions {
      display: flex;
      justify-content: center;
      gap: 16px;
      flex-wrap: wrap;
    }

    /* ============================================
       DARK MODE SUPPORT
       ============================================ */
    
    /* Dark mode via system preference */
    @media (prefers-color-scheme: dark) {
      .subtitle {
        color: rgba(255, 255, 255, 0.7);
      }

      .summary-item {
        background: rgba(255, 255, 255, 0.05);

        mat-icon {
          color: #64b5f6;
        }

        .details .label {
          color: rgba(255, 255, 255, 0.7);
        }

        strong {
          color: rgba(255, 255, 255, 0.92);
        }

        &.late {
          background: rgba(255, 152, 0, 0.15);

          mat-icon {
            color: #ffb74d;
          }

          strong {
            color: #ffb74d;
          }
        }
      }

      .trip-summary h3,
      .next-steps h3 {
        color: rgba(255, 255, 255, 0.92);
      }

      .damage-report {
        background: rgba(255, 152, 0, 0.12);

        .damage-desc {
          color: rgba(255, 255, 255, 0.7);
        }

        .damage-details .detail-row {
          border-bottom-color: rgba(255, 255, 255, 0.1);
          color: rgba(255, 255, 255, 0.87);
        }

        .damage-note {
          color: rgba(255, 255, 255, 0.7);
        }
      }

      .late-fee {
        background: rgba(255, 152, 0, 0.12);

        mat-icon {
          color: #ffb74d;
        }

        strong, span {
          color: rgba(255, 255, 255, 0.92);
        }
      }

      .next-steps ul li {
        color: rgba(255, 255, 255, 0.7);
      }

      mat-divider {
        border-top-color: rgba(255, 255, 255, 0.1);
      }
    }

    /* Dark mode via Angular theme class */
    :host-context(.dark-theme),
    :host-context(.theme-dark) {
      .subtitle {
        color: rgba(255, 255, 255, 0.7);
      }

      .summary-item {
        background: rgba(255, 255, 255, 0.05);

        mat-icon {
          color: #64b5f6;
        }

        .details .label {
          color: rgba(255, 255, 255, 0.7);
        }

        strong {
          color: rgba(255, 255, 255, 0.92);
        }

        &.late {
          background: rgba(255, 152, 0, 0.15);

          mat-icon {
            color: #ffb74d;
          }

          strong {
            color: #ffb74d;
          }
        }
      }

      .trip-summary h3,
      .next-steps h3 {
        color: rgba(255, 255, 255, 0.92);
      }

      .damage-report {
        background: rgba(255, 152, 0, 0.12);

        .damage-desc {
          color: rgba(255, 255, 255, 0.7);
        }

        .damage-details .detail-row {
          border-bottom-color: rgba(255, 255, 255, 0.1);
          color: rgba(255, 255, 255, 0.87);
        }

        .damage-note {
          color: rgba(255, 255, 255, 0.7);
        }
      }

      .late-fee {
        background: rgba(255, 152, 0, 0.12);

        mat-icon {
          color: #ffb74d;
        }

        strong, span {
          color: rgba(255, 255, 255, 0.92);
        }
      }

      .next-steps ul li {
        color: rgba(255, 255, 255, 0.7);
      }

      mat-divider {
        border-top-color: rgba(255, 255, 255, 0.1);
      }
    }
  `],
})
export class CheckoutCompleteComponent {
  @Input() bookingId!: number;
  @Input() status: CheckOutStatusDTO | null = null;

  private router = inject(Router);

  getTripDuration(): string {
    if (!this.status?.tripStartedAt || !this.status?.actualReturnTime) {
      return 'N/A';
    }

    const start = new Date(this.status.tripStartedAt);
    const end = new Date(this.status.actualReturnTime);
    const diffMs = end.getTime() - start.getTime();
    
    const days = Math.floor(diffMs / (1000 * 60 * 60 * 24));
    const hours = Math.floor((diffMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));

    if (days > 0) {
      return `${days}d ${hours}h`;
    }
    return `${hours}h`;
  }

  getDamageStatusLabel(status: string): string {
    return DAMAGE_CLAIM_STATUS_LABELS[status as DamageClaimStatus] || status;
  }

  goToBookings(): void {
    this.router.navigate(['/bookings']);
  }

  goToSearch(): void {
    this.router.navigate(['/search']);
  }
}

