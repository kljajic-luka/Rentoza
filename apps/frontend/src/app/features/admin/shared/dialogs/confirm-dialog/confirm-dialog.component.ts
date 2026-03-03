import { Component, inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { FormsModule } from '@angular/forms';

export interface ConfirmDialogData {
  title: string;
  message: string;
  confirmText?: string;
  confirmColor?: 'primary' | 'accent' | 'warn';
  requireReason?: boolean;
  reasonLabel?: string;
  reasonMinLength?: number;
}

@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [MatDialogModule, MatButtonModule, FormsModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
      @if (data.requireReason) {
        <mat-form-field class="w-full mt-4" appearance="outline">
          <mat-label>{{ data.reasonLabel || 'Reason' }}</mat-label>
          <textarea matInput [(ngModel)]="reason" rows="3"
                    [required]="data.requireReason"></textarea>
          @if (data.reasonMinLength && reason.length > 0 && reason.length < data.reasonMinLength) {
            <mat-hint>{{ reason.length }}/{{ data.reasonMinLength }} characters minimum</mat-hint>
          }
        </mat-form-field>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button
              [color]="data.confirmColor || 'primary'"
              [disabled]="data.requireReason && (!reason.trim() || (data.reasonMinLength ? reason.trim().length < data.reasonMinLength : false))"
              (click)="confirm()">
        {{ data.confirmText || 'Confirm' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: 380px; }
    .w-full { width: 100%; }
    .mt-4 { margin-top: 16px; }
  `]
})
export class ConfirmDialogComponent {
  data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
  private dialogRef = inject(MatDialogRef<ConfirmDialogComponent>);
  reason = '';

  confirm() {
    this.dialogRef.close(this.data.requireReason ? this.reason.trim() : true);
  }
}
