/**
 * Checkout Waiting Component
 *
 * Displays a waiting state when one party has completed their checkout
 * task and is waiting for the other party.
 */
import {
  Component,
  Input,
  Output,
  EventEmitter,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';

import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { CheckOutStatusDTO } from '@core/models/checkout.model';
import { environment } from '@environments/environment';

@Component({
  selector: 'app-checkout-waiting',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatProgressSpinnerModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="checkout-waiting">
      <mat-card class="waiting-card">
        <div class="icon-container" [class.animated]="animate">
          <mat-icon [class.success]="iconType === 'success'">{{ icon }}</mat-icon>
        </div>

        <h2>{{ title }}</h2>
        <p class="message">{{ message }}</p>

        <!-- Status Summary -->
        @if (status) {
          <div class="status-summary">
            <div class="summary-item">
              <mat-icon>check_circle</mat-icon>
              <span>Checkout otvoren</span>
            </div>
            @if (status.guestCheckOutComplete) {
              <div class="summary-item">
                <mat-icon>check_circle</mat-icon>
                <span>Gost završio</span>
              </div>
            }
            @if (status.hostCheckOutComplete) {
              <div class="summary-item">
                <mat-icon>check_circle</mat-icon>
                <span>Domaćin potvrdio</span>
              </div>
            }
          </div>
        }

        <!-- Trip Data (for host waiting) -->
        @if (status?.isHost && status?.endOdometer) {
          <div class="submitted-data">
            <h4>Podaci od gosta:</h4>
            <div class="data-row">
              <span>Završna kilometraža:</span>
              <strong>{{ status!.endOdometer }} km</strong>
            </div>
            <div class="data-row">
              <span>Završno gorivo:</span>
              <strong>{{ status!.endFuelLevel }}%</strong>
            </div>
            @if (status!.totalMileage) {
              <div class="data-row">
                <span>Pređeno:</span>
                <strong>{{ status!.totalMileage }} km</strong>
              </div>
            }
          </div>
        }

        <!-- Next Steps -->
        @if (nextSteps.length > 0) {
          <div class="next-steps">
            <h4>Sledeći koraci:</h4>
            <ul>
              @for (step of nextSteps; track step) {
                <li>{{ step }}</li>
              }
            </ul>
          </div>
        }

        <div class="actions">
          <button
            mat-stroked-button
            (click)="onRefresh()"
            [disabled]="isRefreshing()">
            @if (isRefreshing()) {
              <mat-spinner diameter="20"></mat-spinner>
            } @else {
              <mat-icon>refresh</mat-icon>
            }
            Osveži status
          </button>
        </div>
      </mat-card>
    </div>
  `,
  styles: [`
    .checkout-waiting {
      display: flex;
      justify-content: center;
      padding: 16px;
    }

    .waiting-card {
      max-width: 500px;
      text-align: center;
      padding: 32px 24px;
    }

    .icon-container {
      margin-bottom: 24px;

      mat-icon {
        font-size: 72px;
        width: 72px;
        height: 72px;
        color: var(--primary-color);

        &.success {
          color: var(--success-color, #4caf50);
        }
      }

      &.animated mat-icon {
        animation: pulse 2s ease-in-out infinite;
      }
    }

    @keyframes pulse {
      0%, 100% {
        transform: scale(1);
        opacity: 1;
      }
      50% {
        transform: scale(1.1);
        opacity: 0.8;
      }
    }

    h2 {
      margin: 0 0 8px;
      font-size: 1.5rem;
    }

    .message {
      color: var(--text-secondary);
      margin-bottom: 24px;
    }

    .status-summary {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: 16px;
      margin-bottom: 24px;

      .summary-item {
        display: flex;
        align-items: center;
        gap: 4px;
        font-size: 0.875rem;

        mat-icon {
          font-size: 18px;
          width: 18px;
          height: 18px;
          color: var(--success-color, #4caf50);
        }
      }
    }

    .submitted-data {
      text-align: left;
      padding: 16px;
      background: var(--surface-color);
      border-radius: 8px;
      margin-bottom: 24px;

      h4 {
        margin: 0 0 12px;
        font-size: 0.875rem;
        color: var(--text-secondary);
      }

      .data-row {
        display: flex;
        justify-content: space-between;
        padding: 4px 0;
      }
    }

    .next-steps {
      text-align: left;
      margin-bottom: 24px;

      h4 {
        margin: 0 0 12px;
        font-size: 0.875rem;
        color: var(--text-secondary);
      }

      ul {
        margin: 0;
        padding-left: 20px;

        li {
          margin-bottom: 8px;
          color: var(--text-secondary);
          font-size: 0.875rem;
        }
      }
    }

    .actions {
      button {
        mat-spinner {
          margin-right: 8px;
        }
      }
    }
  `],
})
export class CheckoutWaitingComponent {
  @Input() status: CheckOutStatusDTO | null = null;
  @Input() title = 'Čekanje...';
  @Input() message = '';
  @Input() icon = 'hourglass_empty';
  @Input() iconType: 'waiting' | 'success' = 'waiting';
  @Input() nextSteps: string[] = [];
  @Input() animate = true;

  @Output() refresh = new EventEmitter<void>();

  isRefreshing = signal(false);

  onRefresh(): void {
    this.isRefreshing.set(true);
    this.refresh.emit();
    setTimeout(() => {
      this.isRefreshing.set(false);
    }, 2000);
  }
}

