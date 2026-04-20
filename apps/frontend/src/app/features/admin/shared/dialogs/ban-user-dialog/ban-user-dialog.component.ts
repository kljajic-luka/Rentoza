import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';

@Component({
  selector: 'app-ban-user-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    ReactiveFormsModule,
  ],
  styleUrls: ['../../../admin-shared.styles.scss', './ban-user-dialog.component.scss'],
  template: `
    <h2 mat-dialog-title class="destructive">Ban User</h2>
    <mat-dialog-content>
      <p class="dialog-copy">
        Are you sure you want to ban <strong>{{ data.email }}</strong
        >? This will prevent them from logging in and cancel their active bookings.
      </p>

      <form [formGroup]="banForm">
        <mat-form-field appearance="outline" class="w-full">
          <mat-label>Reason for ban</mat-label>
          <textarea
            matInput
            formControlName="reason"
            rows="3"
            placeholder="e.g. Fraudulent activity..."
          ></textarea>
          <mat-error *ngIf="banForm.get('reason')?.hasError('required')">
            Reason is required
          </mat-error>
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button color="warn" [disabled]="banForm.invalid" (click)="confirm()">
        Ban User
      </button>
    </mat-dialog-actions>
  `,
})
export class BanUserDialogComponent {
  banForm: FormGroup;

  constructor(
    private fb: FormBuilder,
    private dialogRef: MatDialogRef<BanUserDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: { email: string }
  ) {
    this.banForm = this.fb.group({
      reason: ['', [Validators.required, Validators.minLength(5)]],
    });
  }

  confirm() {
    if (this.banForm.valid) {
      this.dialogRef.close(this.banForm.value.reason);
    }
  }
}