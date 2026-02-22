import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

export interface UnbanUserDialogData {
  email: string;
  bannedReason?: string;
}

@Component({
  selector: 'app-unban-user-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>
      <mat-icon class="dialog-icon success">check_circle</mat-icon>
      Unban User
    </h2>
    <mat-dialog-content>
      <p>
        Are you sure you want to unban <strong>{{ data.email }}</strong
        >?
      </p>

      <div class="info-box" *ngIf="data.bannedReason">
        <div class="info-label">Original ban reason:</div>
        <div class="info-value">{{ data.bannedReason }}</div>
      </div>

      <p class="muted">This will restore the user's full access to the platform immediately.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-flat-button color="primary" (click)="onConfirm()">
        <mat-icon>check_circle</mat-icon>
        Unban User
      </button>
    </mat-dialog-actions>
  `,
  styles: [
    `
      .dialog-icon {
        vertical-align: middle;
        margin-right: 8px;
      }
      .dialog-icon.success {
        color: #4caf50;
      }
      .info-box {
        background: #f5f5f5;
        border-radius: 8px;
        padding: 12px 16px;
        margin: 16px 0;
      }
      .info-label {
        font-size: 12px;
        color: #666;
        margin-bottom: 4px;
      }
      .info-value {
        font-size: 14px;
        color: #333;
      }
      .muted {
        color: #666;
        font-size: 14px;
      }
      mat-dialog-content {
        min-width: 350px;
      }
      mat-dialog-actions button {
        margin-left: 8px;
      }
    `,
  ],
})
export class UnbanUserDialogComponent {
  constructor(
    public dialogRef: MatDialogRef<UnbanUserDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: UnbanUserDialogData,
  ) {}

  onCancel(): void {
    this.dialogRef.close(false);
  }

  onConfirm(): void {
    this.dialogRef.close(true);
  }
}