/**
 * Check-In Recovery Dialog Component
 *
 * Shows when a saved check-in session is detected on page load.
 * Allows users to resume their previous progress or start fresh.
 *
 * ## UX Flow
 * 1. Component detects saved session via CheckInPersistenceService
 * 2. Shows dialog: "Found previous session (6/8 photos, 5 min ago)"
 * 3. Options: [Resume] [Start Fresh] [Cancel]
 * 4. On Resume: Restores state and continues from last photo
 * 5. On Start Fresh: Clears saved data and starts over
 *
 * ## Multi-Tab Warning
 * If session is owned by another tab, shows:
 * "Session active in another tab. [Takeover] [View Only]"
 */

import { Component, inject, signal, ChangeDetectionStrategy, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import {
  SavedSessionInfo,
  CaptureState,
  CheckInPersistenceService,
} from '@core/services/check-in-persistence.service';

export interface RecoveryDialogData {
  sessionInfo: SavedSessionInfo;
  bookingId: number;
  mode: CaptureState['mode'];
}

export type RecoveryDialogResult =
  | { action: 'resume'; captureState: CaptureState }
  | { action: 'start-fresh' }
  | { action: 'cancel' }
  | { action: 'takeover'; captureState: CaptureState }
  | { action: 'view-only' };

@Component({
  selector: 'app-check-in-recovery-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="recovery-dialog">
      <!-- Header -->
      <div class="dialog-header">
        <mat-icon class="header-icon" [class.warning]="data.sessionInfo.isOwnedByOtherTab">
          {{ data.sessionInfo.isOwnedByOtherTab ? 'warning' : 'restore' }}
        </mat-icon>
        <h2>
          {{
            data.sessionInfo.isOwnedByOtherTab
              ? 'Sesija aktivna u drugom tabu'
              : 'Pronađena prethodna sesija'
          }}
        </h2>
      </div>

      <!-- Session Info -->
      <div class="session-info">
        @if (!data.sessionInfo.isOwnedByOtherTab) {
        <div class="info-row">
          <mat-icon>photo_camera</mat-icon>
          <span>
            <strong>{{ data.sessionInfo.photoCount }}</strong> od
            <strong>{{ data.sessionInfo.totalPhotos }}</strong> fotografija snimljeno
          </span>
        </div>

        <div class="info-row">
          <mat-icon>schedule</mat-icon>
          <span
            >Sačuvano pre <strong>{{ formatTimeAgo(data.sessionInfo.minutesAgo!) }}</strong></span
          >
        </div>

        <div class="progress-bar">
          <div
            class="progress-fill"
            [style.width.%]="(data.sessionInfo.photoCount! / data.sessionInfo.totalPhotos!) * 100"
          ></div>
        </div>
        } @else {
        <div class="info-row warning">
          <mat-icon>tab</mat-icon>
          <span>
            Ova sesija je trenutno otvorena u drugom tabu pregledača. Možete preuzeti kontrolu ili
            samo pregledati.
          </span>
        </div>
        }
      </div>

      <!-- Actions -->
      <div class="dialog-actions">
        @if (isLoading()) {
        <mat-spinner diameter="24"></mat-spinner>
        } @else if (!data.sessionInfo.isOwnedByOtherTab) {
        <!-- Normal recovery options -->
        <button mat-button (click)="startFresh()">
          <mat-icon>refresh</mat-icon>
          Počni ispočetka
        </button>

        <button mat-flat-button color="primary" (click)="resume()">
          <mat-icon>play_arrow</mat-icon>
          Nastavi gde si stao
        </button>
        } @else {
        <!-- Multi-tab options -->
        <button mat-button (click)="cancel()">Odustani</button>

        <button mat-button color="warn" (click)="takeover()">
          <mat-icon>swap_horiz</mat-icon>
          Preuzmi kontrolu
        </button>
        }
      </div>

      <!-- Warning for start fresh -->
      @if (!data.sessionInfo.isOwnedByOtherTab && !isLoading()) {
      <p class="warning-text">
        <mat-icon>info</mat-icon>
        "Počni ispočetka" će izbrisati sve sačuvane fotografije.
      </p>
      }
    </div>
  `,
  styles: [
    `
      .recovery-dialog {
        padding: 24px;
        max-width: 400px;
      }

      .dialog-header {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 20px;
      }

      .header-icon {
        font-size: 32px;
        width: 32px;
        height: 32px;
        color: var(--brand-primary);
      }

      .header-icon.warning {
        color: var(--warn-color, #ff9800);
      }

      .dialog-header h2 {
        margin: 0;
        font-size: 20px;
        font-weight: 500;
      }

      .session-info {
        margin-bottom: 24px;
      }

      .info-row {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 12px;
        color: var(--color-text-secondary, #666);
      }

      .info-row mat-icon {
        font-size: 20px;
        width: 20px;
        height: 20px;
        color: var(--color-text-muted, #999);
      }

      .info-row.warning {
        background: var(--warn-bg, #fff3e0);
        padding: 12px;
        border-radius: 8px;
        color: var(--warn-color, #e65100);
      }

      .info-row.warning mat-icon {
        color: var(--warn-color, #e65100);
      }

      .progress-bar {
        height: 8px;
        background: var(--color-border, #e0e0e0);
        border-radius: 4px;
        overflow: hidden;
        margin-top: 8px;
      }

      .progress-fill {
        height: 100%;
        background: var(--brand-primary);
        border-radius: 4px;
        transition: width 0.3s ease;
      }

      .dialog-actions {
        display: flex;
        justify-content: flex-end;
        gap: 12px;
        margin-bottom: 16px;
      }

      .dialog-actions button {
        display: flex;
        align-items: center;
        gap: 8px;
      }

      .warning-text {
        display: flex;
        align-items: center;
        gap: 8px;
        margin: 0;
        font-size: 12px;
        color: var(--color-text-muted, #999);
      }

      .warning-text mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
      }

      /* Dark mode */
      :host-context(.dark-theme),
      :host-context(.theme-dark) {
        .dialog-header h2 {
          color: rgba(255, 255, 255, 0.95);
        }

        .info-row {
          color: rgba(255, 255, 255, 0.7);
        }

        .info-row mat-icon {
          color: rgba(255, 255, 255, 0.5);
        }

        .info-row.warning {
          background: rgba(255, 152, 0, 0.15);
          color: #ffb74d;
        }

        .info-row.warning mat-icon {
          color: #ffb74d;
        }

        .progress-bar {
          background: rgba(255, 255, 255, 0.1);
        }

        .warning-text {
          color: rgba(255, 255, 255, 0.5);
        }
      }

    `,
  ],
})
export class CheckInRecoveryDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<CheckInRecoveryDialogComponent>);
  protected readonly data = inject<RecoveryDialogData>(MAT_DIALOG_DATA);
  private readonly persistenceService = inject(CheckInPersistenceService);

  protected readonly isLoading = signal(false);

  protected formatTimeAgo(minutes: number): string {
    if (minutes < 1) return 'manje od minuta';
    if (minutes === 1) return '1 minut';
    if (minutes < 60) return `${minutes} minuta`;
    const hours = Math.floor(minutes / 60);
    if (hours === 1) return '1 sat';
    if (hours < 24) return `${hours} sata`;
    const days = Math.floor(hours / 24);
    return days === 1 ? '1 dan' : `${days} dana`;
  }

  protected async resume(): Promise<void> {
    this.isLoading.set(true);

    try {
      const captureState = await this.persistenceService.loadCaptureState(
        this.data.bookingId,
        this.data.mode
      );

      if (captureState) {
        this.dialogRef.close({ action: 'resume', captureState } as RecoveryDialogResult);
      } else {
        // State was deleted/expired - start fresh
        this.dialogRef.close({ action: 'start-fresh' } as RecoveryDialogResult);
      }
    } catch (error) {
      console.error('[Recovery] Failed to load capture state:', error);
      this.dialogRef.close({ action: 'start-fresh' } as RecoveryDialogResult);
    }
  }

  protected async startFresh(): Promise<void> {
    this.isLoading.set(true);

    try {
      await this.persistenceService.deleteCaptureState(this.data.bookingId, this.data.mode);
      await this.persistenceService.deleteFormState(this.data.bookingId, this.data.mode);
      this.dialogRef.close({ action: 'start-fresh' } as RecoveryDialogResult);
    } catch (error) {
      console.error('[Recovery] Failed to clear state:', error);
      this.dialogRef.close({ action: 'start-fresh' } as RecoveryDialogResult);
    }
  }

  protected cancel(): void {
    this.dialogRef.close({ action: 'cancel' } as RecoveryDialogResult);
  }

  protected async takeover(): Promise<void> {
    this.isLoading.set(true);

    try {
      const success = await this.persistenceService.requestTakeover(this.data.bookingId);

      if (success) {
        const captureState = await this.persistenceService.loadCaptureState(
          this.data.bookingId,
          this.data.mode
        );

        if (captureState) {
          this.dialogRef.close({ action: 'takeover', captureState } as RecoveryDialogResult);
        } else {
          this.dialogRef.close({ action: 'start-fresh' } as RecoveryDialogResult);
        }
      } else {
        // Takeover denied - other tab is still active
        this.isLoading.set(false);
      }
    } catch (error) {
      console.error('[Recovery] Takeover failed:', error);
      this.isLoading.set(false);
    }
  }
}