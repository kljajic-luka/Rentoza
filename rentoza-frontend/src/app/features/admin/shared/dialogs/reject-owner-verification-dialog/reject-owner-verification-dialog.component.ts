import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';

@Component({
  selector: 'app-reject-owner-verification-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    ReactiveFormsModule,
  ],
  styleUrls: [
    '../../../admin-shared.styles.scss',
    './reject-owner-verification-dialog.component.scss',
  ],
  template: `
    <h2 mat-dialog-title class="destructive">Reject Owner Verification</h2>
    <mat-dialog-content>
      <p class="dialog-copy">
        Provide a reason for rejecting verification for <strong>{{ data.displayName }}</strong
        >. The owner will need to resubmit.
      </p>

      <form [formGroup]="form">
        <mat-form-field appearance="outline" class="w-full">
          <mat-label>Rejection reason</mat-label>
          <textarea
            matInput
            formControlName="reason"
            rows="3"
            placeholder="e.g. ID number does not match account holder..."
          ></textarea>
          <mat-error *ngIf="form.get('reason')?.hasError('required')">Reason is required</mat-error>
          <mat-error *ngIf="form.get('reason')?.hasError('minlength')">
            Reason must be at least 5 characters
          </mat-error>
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button color="warn" [disabled]="form.invalid" (click)="confirm()">
        Reject
      </button>
    </mat-dialog-actions>
  `,
})
export class RejectOwnerVerificationDialogComponent {
  form: FormGroup;

  constructor(
    private fb: FormBuilder,
    private dialogRef: MatDialogRef<RejectOwnerVerificationDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: { displayName: string }
  ) {
    this.form = this.fb.group({
      reason: ['', [Validators.required, Validators.minLength(5), Validators.maxLength(500)]],
    });
  }

  confirm() {
    if (this.form.valid) {
      this.dialogRef.close(this.form.value.reason);
    }
  }
}
