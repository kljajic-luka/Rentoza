import { Component, inject, Input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/**
 * Styled Confirmation Dialog — Category 4, Interaction 6
 *
 * Replaces all window.confirm() calls with a styled modal.
 * Emits confirmed=true when user clicks the destructive action,
 * or confirmed=false when cancelled.
 *
 * Usage:
 * <app-confirm-dialog
 *   [title]="'Otkaži rezervaciju'"
 *   [message]="'Da li si siguran? Ovo se ne može poništiti.'"
 *   [confirmLabel]="'Da, otkaži'"
 *   [cancelLabel]="'Ne, zadrži'"
 *   [danger]="true"
 *   (confirmed)="onConfirm($event)"
 * />
 */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [MatButtonModule, MatIconModule],
  template: `
    <!-- Backdrop -->
    <div class="confirm-backdrop" (click)="cancel()" aria-hidden="true"></div>

    <!-- Dialog panel -->
    <div
      class="confirm-panel"
      role="alertdialog"
      [attr.aria-label]="title"
      [attr.aria-describedby]="'confirm-msg-' + uid"
    >
      <div class="confirm-header">
        @if (danger) {
          <div class="confirm-icon confirm-icon--danger" aria-hidden="true">
            <mat-icon>warning</mat-icon>
          </div>
        }
        <h2 class="confirm-title">{{ title }}</h2>
      </div>

      <p class="confirm-message" [id]="'confirm-msg-' + uid">{{ message }}</p>

      <div class="confirm-actions">
        <button
          mat-stroked-button
          class="confirm-btn confirm-btn--cancel"
          (click)="cancel()"
          type="button"
        >{{ cancelLabel }}</button>
        <button
          mat-flat-button
          class="confirm-btn"
          [class.confirm-btn--danger]="danger"
          [class.confirm-btn--primary]="!danger"
          (click)="confirm()"
          type="button"
          cdkFocusInitial
        >{{ confirmLabel }}</button>
      </div>
    </div>
  `,
  styles: [`
    :host {
      position: fixed;
      inset: 0;
      z-index: 10000;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 16px;
    }

    .confirm-backdrop {
      position: absolute;
      inset: 0;
      background: rgba(18, 18, 20, 0.5);
      animation: backdrop-in 0.2s ease forwards;
    }

    @keyframes backdrop-in {
      from { opacity: 0; }
      to   { opacity: 1; }
    }

    .confirm-panel {
      position: relative;
      z-index: 1;
      background: var(--color-surface, #fff);
      border-radius: var(--radius-xl, 24px);
      padding: 28px;
      width: 100%;
      max-width: 400px;
      box-shadow: var(--shadow-xl);
      animation: panel-in 0.2s cubic-bezier(0.0, 0.0, 0.2, 1) forwards;
    }

    @keyframes panel-in {
      from {
        opacity: 0;
        transform: scale(0.95);
      }
      to {
        opacity: 1;
        transform: scale(1);
      }
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

    @media (prefers-reduced-motion: reduce) {
      .confirm-backdrop,
      .confirm-panel {
        animation: none !important;
        opacity: 1 !important;
        transform: none !important;
      }
    }
  `],
})
export class ConfirmDialogComponent {
  @Input() title = 'Da li si siguran?';
  @Input() message = 'Ova akcija se ne može poništiti.';
  @Input() confirmLabel = 'Potvrdi';
  @Input() cancelLabel = 'Otkaži';
  @Input() danger = false;

  readonly confirmed = output<boolean>();

  /** Unique id for aria-describedby */
  readonly uid = Math.random().toString(36).slice(2, 8);

  confirm(): void {
    this.confirmed.emit(true);
  }

  cancel(): void {
    this.confirmed.emit(false);
  }
}