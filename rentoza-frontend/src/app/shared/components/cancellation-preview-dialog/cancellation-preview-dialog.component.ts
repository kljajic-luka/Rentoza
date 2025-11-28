import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule, CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';

import { CancellationPolicyService } from '@core/services/cancellation-policy.service';
import {
  CancellationPreview,
  CancellationReason,
  CancellationResult,
  CANCELLATION_REASON_LABELS,
  APPLIED_RULE_LABELS,
  getGuestReasons,
  getHostReasons,
} from '@core/models/cancellation.model';

/**
 * Dialog input data for cancellation preview.
 */
export interface CancellationPreviewDialogData {
  /** Booking to cancel */
  bookingId: number;
  /** Who is initiating (for showing correct reasons) */
  userRole: 'GUEST' | 'HOST';
  /** Car info for display */
  carInfo?: string;
  /** Trip dates for display */
  tripDates?: string;
}

/**
 * Dialog output - what the parent component receives on close.
 */
export interface CancellationPreviewDialogResult {
  /** Whether cancellation was confirmed */
  confirmed: boolean;
  /** The result if confirmed */
  result?: CancellationResult;
}

/**
 * Cancellation Preview Dialog Component.
 *
 * Shows the user the financial consequences of cancelling before they commit.
 *
 * Features:
 * - Fetches cancellation preview from API on init
 * - Shows penalty/refund breakdown with color coding
 * - Displays which rule was applied (24h free, remorse window, etc.)
 * - Reason dropdown for selection
 * - Optional notes field
 * - Confirm/Cancel buttons
 * - Loading and error states
 *
 * @since 2024-01 (Cancellation Policy Migration - Phase 3)
 */
@Component({
  selector: 'app-cancellation-preview-dialog',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatSelectModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    CurrencyPipe,
  ],
  template: `
    <h2 mat-dialog-title class="dialog-title">
      <mat-icon>warning</mat-icon>
      Otkazivanje rezervacije
    </h2>

    <mat-dialog-content class="dialog-content">
      <!-- Loading State -->
      @if (isLoading()) {
      <div class="loading-container">
        <mat-spinner diameter="48"></mat-spinner>
        <p>Učitavanje detalja...</p>
      </div>
      }

      <!-- Error State -->
      @if (error()) {
      <div class="error-container">
        <mat-icon color="warn">error</mat-icon>
        <p>{{ error() }}</p>
        <button mat-button color="primary" (click)="loadPreview()">Pokušaj ponovo</button>
      </div>
      }

      <!-- Preview Content -->
      @if (preview() && !isLoading() && !error()) {
      <!-- Trip Info -->
      @if (data.carInfo || data.tripDates) {
      <div class="trip-info">
        @if (data.carInfo) {
        <p class="car-name">{{ data.carInfo }}</p>
        } @if (data.tripDates) {
        <p class="trip-dates">{{ data.tripDates }}</p>
        }
      </div>
      <mat-divider></mat-divider>
      }

      <!-- Financial Breakdown -->
      <div class="financial-breakdown">
        <h3>Finansijski pregled</h3>

        <div class="breakdown-row">
          <span class="label">Cena putovanja:</span>
          <span class="value">{{
            preview()!.originalTotal | currency : 'RSD' : 'symbol' : '1.0-0'
          }}</span>
        </div>

        <div class="breakdown-row penalty" [class.zero]="preview()!.penaltyAmount === 0">
          <span class="label">Penal:</span>
          <span class="value">
            @if (preview()!.penaltyAmount > 0) { -
            {{ preview()!.penaltyAmount | currency : 'RSD' : 'symbol' : '1.0-0' }}
            } @else { Nema penala }
          </span>
        </div>

        <mat-divider></mat-divider>

        <div class="breakdown-row refund">
          <span class="label">Povraćaj:</span>
          <span class="value highlight">
            {{ preview()!.refundAmount | currency : 'RSD' : 'symbol' : '1.0-0' }}
          </span>
        </div>
      </div>

      <!-- Applied Rule Explanation -->
      <div class="rule-explanation" [ngClass]="ruleClass()">
        <mat-icon>{{ ruleIcon() }}</mat-icon>
        <div>
          <strong>{{ getRuleLabel(preview()!.appliedRule) }}</strong>
          <p class="rule-detail">
            @if (preview()!.isWithinFreeWindow) { Otkazujete više od 24 sata pre početka putovanja.
            } @else if (preview()!.isWithinRemorseWindow) { Otkazujete u roku od 1 sat od
            rezervacije. } @else if (preview()!.hoursUntilStart <= 0) { Putovanje je već počelo ili
            ste propustili preuzimanje. } @else { Otkazujete manje od 24 sata pre početka putovanja.
            }
          </p>
        </div>
      </div>

      <!-- Host Penalty Warning (only for hosts) -->
      @if (data.userRole === 'HOST' && preview()!.hostPenalty) {
      <div class="host-penalty-warning">
        <mat-icon color="warn">gpp_maybe</mat-icon>
        <div>
          <strong>Penali za vlasnika</strong>
          <p>
            Ovim otkazivanjem bićete naplaćeni
            <strong>{{
              preview()!.hostPenalty!.monetaryPenalty | currency : 'RSD' : 'symbol' : '1.0-0'
            }}</strong
            >.
          </p>
          @if (preview()!.hostPenalty!.willTriggerSuspension) {
          <p class="suspension-warning">
            ⚠️ Ovo otkazivanje će aktivirati 7-dnevnu suspenziju vašeg naloga.
          </p>
          }
        </div>
      </div>
      }

      <mat-divider></mat-divider>

      <!-- Reason Selection -->
      <div class="reason-selection">
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Razlog otkazivanja</mat-label>
          <mat-select
            [value]="selectedReason()"
            (selectionChange)="onReasonChange($event.value)"
            required
          >
            @for (reason of availableReasons(); track reason) {
            <mat-option [value]="reason">
              {{ getReasonLabel(reason) }}
            </mat-option>
            }
          </mat-select>
          @if (!selectedReason()) {
          <mat-error>Izaberite razlog otkazivanja</mat-error>
          }
        </mat-form-field>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Dodatne napomene (opciono)</mat-label>
          <textarea
            matInput
            [(ngModel)]="notes"
            rows="2"
            maxlength="500"
            placeholder="Opišite situaciju..."
          ></textarea>
          <mat-hint align="end">{{ notes.length }}/500</mat-hint>
        </mat-form-field>
      </div>
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button (click)="onCancel()" [disabled]="isConfirming()">Odustani</button>
      <button mat-raised-button color="warn" (click)="onConfirm()" [disabled]="!canConfirm()">
        @if (isConfirming()) {
        <mat-spinner diameter="20"></mat-spinner>
        } @else {
        <ng-container>
          <mat-icon>cancel</mat-icon>
          Potvrdi otkazivanje
        </ng-container>
        }
      </button>
    </mat-dialog-actions>
  `,
  styles: [
    `
      .dialog-title {
        display: flex;
        align-items: center;
        gap: 8px;

        mat-icon {
          color: var(--mat-warn-500);
        }
      }

      .dialog-content {
        min-width: 400px;
        max-width: 500px;
      }

      .loading-container,
      .error-container {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        padding: 32px;
        gap: 16px;
        text-align: center;
      }

      .error-container mat-icon {
        font-size: 48px;
        width: 48px;
        height: 48px;
      }

      .trip-info {
        padding: 16px 0;

        .car-name {
          font-size: 18px;
          font-weight: 500;
          margin: 0;
        }

        .trip-dates {
          color: var(--mat-sys-on-surface-variant);
          margin: 4px 0 0;
        }
      }

      .financial-breakdown {
        padding: 16px 0;

        h3 {
          margin: 0 0 16px;
          font-size: 16px;
        }

        .breakdown-row {
          display: flex;
          justify-content: space-between;
          padding: 8px 0;

          .label {
            color: var(--mat-sys-on-surface-variant);
          }

          .value {
            font-weight: 500;
          }

          &.penalty .value {
            color: var(--mat-warn-500);
          }

          &.penalty.zero .value {
            color: var(--mat-sys-on-surface-variant);
          }

          &.refund {
            font-size: 18px;

            .highlight {
              color: var(--mat-sys-primary);
            }
          }
        }
      }

      .rule-explanation {
        display: flex;
        gap: 12px;
        padding: 16px;
        border-radius: 8px;
        margin: 16px 0;

        mat-icon {
          flex-shrink: 0;
        }

        strong {
          display: block;
          margin-bottom: 4px;
        }

        .rule-detail {
          margin: 0;
          font-size: 14px;
          color: var(--mat-sys-on-surface-variant);
        }

        &.free {
          background: rgba(76, 175, 80, 0.1);
          mat-icon {
            color: #4caf50;
          }
        }

        &.penalty {
          background: rgba(255, 152, 0, 0.1);
          mat-icon {
            color: #ff9800;
          }
        }

        &.severe {
          background: rgba(244, 67, 54, 0.1);
          mat-icon {
            color: #f44336;
          }
        }
      }

      .host-penalty-warning {
        display: flex;
        gap: 12px;
        padding: 16px;
        background: rgba(244, 67, 54, 0.1);
        border-radius: 8px;
        margin: 16px 0;

        mat-icon {
          flex-shrink: 0;
          font-size: 24px;
          width: 24px;
          height: 24px;
        }

        p {
          margin: 4px 0;
        }

        .suspension-warning {
          color: var(--mat-warn-500);
          font-weight: 500;
        }
      }

      .reason-selection {
        padding: 16px 0;

        .full-width {
          width: 100%;
        }
      }

      mat-dialog-actions {
        padding: 16px 24px;
      }
    `,
  ],
})
export class CancellationPreviewDialogComponent implements OnInit {
  private readonly dialogRef = inject(MatDialogRef<CancellationPreviewDialogComponent>);
  private readonly cancellationService = inject(CancellationPolicyService);
  protected readonly data: CancellationPreviewDialogData = inject(MAT_DIALOG_DATA);

  // State signals
  protected readonly isLoading = signal(true); // Start as true since we load on init
  protected readonly isConfirming = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly preview = signal<CancellationPreview | null>(null);

  // Form state - use signals for reactivity
  protected readonly selectedReason = signal<CancellationReason | null>(null);
  protected notes = '';

  // Computed values
  protected readonly availableReasons = computed(() => {
    return this.data.userRole === 'HOST' ? getHostReasons() : getGuestReasons();
  });

  protected readonly canConfirm = computed(() => {
    const loading = this.isLoading();
    const confirming = this.isConfirming();
    const hasError = this.error();
    const hasPreview = this.preview() !== null;
    const hasReason = this.selectedReason() !== null;

    // Debug logging (can remove after verification)
    console.debug('[CancellationDialog] canConfirm check:', {
      loading,
      confirming,
      hasError,
      hasPreview,
      hasReason,
      result: !loading && !confirming && !hasError && hasPreview && hasReason,
    });

    return !loading && !confirming && !hasError && hasPreview && hasReason;
  });

  protected readonly ruleClass = computed(() => {
    const p = this.preview();
    if (!p) return '';

    if (p.isWithinFreeWindow || p.isWithinRemorseWindow) return 'free';
    if (p.hoursUntilStart <= 0) return 'severe';
    return 'penalty';
  });

  protected readonly ruleIcon = computed(() => {
    const p = this.preview();
    if (!p) return 'help';

    if (p.isWithinFreeWindow || p.isWithinRemorseWindow) return 'check_circle';
    if (p.hoursUntilStart <= 0) return 'error';
    return 'warning';
  });

  ngOnInit(): void {
    this.loadPreview();
  }

  /**
   * Handle reason selection change.
   */
  protected onReasonChange(reason: CancellationReason): void {
    this.selectedReason.set(reason);
  }

  /**
   * Load cancellation preview from API.
   */
  protected loadPreview(): void {
    this.isLoading.set(true);
    this.error.set(null);

    this.cancellationService.getPreview(this.data.bookingId).subscribe({
      next: (preview) => {
        console.debug('[CancellationDialog] Preview loaded:', preview);
        this.preview.set(preview);
        this.isLoading.set(false);
      },
      error: (err) => {
        console.error('[CancellationDialog] Preview load error:', err);
        // Extract error message from various response formats
        const message = err.error?.message || err.message || 'Greška pri učitavanju.';
        this.error.set(message);
        this.isLoading.set(false);
      },
    });
  }

  /**
   * Confirm cancellation and close dialog.
   */
  protected onConfirm(): void {
    const reason = this.selectedReason();
    if (!reason) return;

    this.isConfirming.set(true);

    this.cancellationService
      .confirmCancellation(this.data.bookingId, {
        reason: reason,
        notes: this.notes || undefined,
      })
      .subscribe({
        next: (result) => {
          this.isConfirming.set(false);
          this.dialogRef.close({
            confirmed: true,
            result,
          } as CancellationPreviewDialogResult);
        },
        error: (err) => {
          this.isConfirming.set(false);
          this.error.set(err.error?.message || err.message || 'Greška pri otkazivanju.');
        },
      });
  }

  /**
   * Cancel and close dialog without action.
   */
  protected onCancel(): void {
    this.dialogRef.close({ confirmed: false } as CancellationPreviewDialogResult);
  }

  /**
   * Get human-readable label for a reason.
   */
  protected getReasonLabel(reason: CancellationReason): string {
    return CANCELLATION_REASON_LABELS[reason] || reason;
  }

  /**
   * Get human-readable label for applied rule.
   */
  protected getRuleLabel(rule: string): string {
    return APPLIED_RULE_LABELS[rule] || rule;
  }
}
