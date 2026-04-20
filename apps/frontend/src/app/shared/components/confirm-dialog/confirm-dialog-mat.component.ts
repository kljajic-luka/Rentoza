import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';

export interface ConfirmDialogData {
  title?: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
}

/**
 * MatDialog-compatible confirmation dialog.
 * Used by ConfirmDialogService to replace window.confirm() calls.
 */
@Component({
  selector: 'app-confirm-dialog-mat',
  standalone: true,
  imports: [MatButtonModule, MatIconModule],
  template: `
    <div class="confirm-panel" role="alertdialog" [attr.aria-label]="data.title">
      <div class="confirm-header">
        @if (data.danger) {
          <div class="confirm-icon confirm-icon--danger" aria-hidden="true">
            <mat-icon>warning</mat-icon>
          </div>
        }
        <h2 class="confirm-title">{{ data.title ?? 'Da li ste sigurni?' }}</h2>
      </div>

      <p class="confirm-message" id="confirm-message">{{ data.message }}</p>

      <div class="confirm-actions">
        <button
          mat-stroked-button
          class="confirm-btn confirm-btn--cancel"
          (click)="cancel()"
          type="button"
        >{{ data.cancelLabel ?? 'Otkaži' }}</button>
        <button
          mat-flat-button
          class="confirm-btn"
          [class.confirm-btn--danger]="data.danger"
          [class.confirm-btn--primary]="!data.danger"
          (click)="confirm()"
          type="button"
          cdkFocusInitial
        >{{ data.confirmLabel ?? 'Potvrdi' }}</button>
      </div>
    </div>
  `,
  styles: [`
    .confirm-panel {
      padding: 8px 0 0;
    }

    .confirm-header {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 12px;
    }

    .confirm-icon {
      flex-shrink: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 40px;
      height: 40px;
      border-radius: var(--radius-full, 9999px);
    }

    .confirm-icon--danger {
      background: rgba(255, 90, 95, 0.12);
      color: var(--color-error, #FF5A5F);
    }

    .confirm-title {
      margin: 0;
      font-size: var(--font-size-lg, 1.125rem);
      font-weight: 700;
      color: var(--color-text-primary);
      line-height: 1.3;
    }

    .confirm-message {
      margin: 0 0 24px;
      font-size: var(--font-size-base, 1rem);
      color: var(--color-text-secondary);
      line-height: 1.5;
    }

    .confirm-actions {
      display: flex;
      gap: 12px;
      justify-content: flex-end;
    }

    .confirm-btn {
      min-width: 100px;
      font-weight: 600 !important;
    }

    .confirm-btn--cancel {
      color: var(--color-text-secondary) !important;
    }

    .confirm-btn--danger {
      background: var(--color-error, #FF5A5F) !important;
      color: #fff !important;
    }

    .confirm-btn--primary {
      background: var(--brand-primary, #593CFB) !important;
      color: #fff !important;
    }
  `],
})
export class ConfirmDialogMatComponent {
  protected readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<ConfirmDialogMatComponent, boolean>);

  confirm(): void {
    this.dialogRef.close(true);
  }

  cancel(): void {
    this.dialogRef.close(false);
  }
}