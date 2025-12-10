import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';

export interface CarActionDialogData {
  action: 'approve' | 'reject' | 'suspend' | 'reactivate';
  carBrand: string;
  carModel: string;
}

@Component({
  selector: 'app-car-action-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    ReactiveFormsModule,
  ],
  styleUrls: ['../../../admin-shared.styles.scss', './car-action-dialog.component.scss'],
  template: `
    <h2 mat-dialog-title [ngClass]="{ destructive: isDestructive, positive: isPositive }">
      {{ title }}
    </h2>
    <mat-dialog-content>
      <p class="dialog-copy">
        {{ message }}
      </p>

      <form [formGroup]="form" *ngIf="requiresReason">
        <mat-form-field appearance="outline" class="w-full">
          <mat-label>Reason / Notes</mat-label>
          <textarea
            matInput
            formControlName="reason"
            rows="3"
            placeholder="Provide a reason..."
          ></textarea>
          <mat-error *ngIf="form.get('reason')?.hasError('required')">
            Reason is required
          </mat-error>
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button
        mat-flat-button
        [color]="color"
        [disabled]="requiresReason && form.invalid"
        (click)="confirm()"
      >
        {{ actionLabel }}
      </button>
    </mat-dialog-actions>
  `,
})
export class CarActionDialogComponent {
  form: FormGroup;

  constructor(
    private fb: FormBuilder,
    private dialogRef: MatDialogRef<CarActionDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: CarActionDialogData
  ) {
    this.form = this.fb.group({
      reason: ['', this.requiresReason ? [Validators.required, Validators.minLength(5)] : []],
    });
  }

  get requiresReason(): boolean {
    return this.data.action === 'reject' || this.data.action === 'suspend';
  }

  get isDestructive(): boolean {
    return this.data.action === 'reject' || this.data.action === 'suspend';
  }

  get isPositive(): boolean {
    return this.data.action === 'approve' || this.data.action === 'reactivate';
  }

  get title(): string {
    switch (this.data.action) {
      case 'approve':
        return 'Approve Car Listing';
      case 'reject':
        return 'Reject Car Listing';
      case 'suspend':
        return 'Suspend Car Listing';
      case 'reactivate':
        return 'Reactivate Car Listing';
      default:
        return 'Confirm Action';
    }
  }

  get message(): string {
    const carName = `${this.data.carBrand} ${this.data.carModel}`;
    switch (this.data.action) {
      case 'approve':
        return `Are you sure you want to approve the ${carName}? It will become visible to users immediately.`;
      case 'reject':
        return `Are you sure you want to reject the ${carName}? Please provide a reason for the owner.`;
      case 'suspend':
        return `Are you sure you want to suspend the ${carName}? It will be hidden from search results.`;
      case 'reactivate':
        return `Are you sure you want to reactivate the ${carName}?`;
      default:
        return 'Are you sure?';
    }
  }

  get actionLabel(): string {
    return this.data.action.charAt(0).toUpperCase() + this.data.action.slice(1);
  }

  get color(): string {
    return this.isDestructive ? 'warn' : 'primary';
  }

  confirm() {
    if (this.requiresReason && this.form.invalid) return;
    this.dialogRef.close({ confirmed: true, reason: this.form.value.reason });
  }
}
