import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';

import { REJECTION_REASONS } from '../../../../../core/models/admin-renter-verification.model';

export interface RejectRenterVerificationDialogData {
  displayName: string;
}

export interface RejectRenterVerificationDialogResult {
  reason: string;
  allowResubmission: boolean;
}

/**
 * Rejection Dialog
 * 
 * Dropdown of common reasons with custom option.
 */
@Component({
  selector: 'app-reject-renter-verification-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatIconModule,
    ReactiveFormsModule,
  ],
  styleUrls: [
    '../../../admin-shared.styles.scss',
    './reject-renter-verification-dialog.component.scss',
  ],
  template: `
    <h2 mat-dialog-title class="destructive">
      <mat-icon style="color: #ef4444; margin-right: 8px;">cancel</mat-icon>
      Odbij verifikaciju
    </h2>
    
    <mat-dialog-content>
      <p class="dialog-copy">
        Navedite razlog odbijanja verifikacije vozačke dozvole za
        <strong>{{ data.displayName }}</strong>.
        Korisnik će moći ponovo da podnese dokumenta.
      </p>
      
      <form [formGroup]="form">
        <mat-form-field appearance="outline" class="w-full">
          <mat-label>Razlog odbijanja</mat-label>
          <mat-select formControlName="reasonType" (selectionChange)="onReasonTypeChange()">
            <mat-option *ngFor="let reason of rejectionReasons" [value]="reason.value">
              {{ reason.label }}
            </mat-option>
          </mat-select>
        </mat-form-field>
        
        <mat-form-field appearance="outline" class="w-full" *ngIf="showCustomReason">
          <mat-label>Unesite razlog</mat-label>
          <textarea
            matInput
            formControlName="customReason"
            rows="3"
            placeholder="Opišite razlog odbijanja..."
          ></textarea>
          <mat-error *ngIf="form.get('customReason')?.hasError('required')">
            Razlog je obavezan
          </mat-error>
          <mat-error *ngIf="form.get('customReason')?.hasError('minlength')">
            Razlog mora imati najmanje 10 karaktera
          </mat-error>
        </mat-form-field>
        
        <div class="checkbox-row">
          <mat-checkbox formControlName="allowResubmission" color="primary">
            Dozvoli korisniku da ponovo podnese dokumenta
          </mat-checkbox>
        </div>
      </form>
    </mat-dialog-content>
    
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Otkaži</button>
      <button mat-flat-button color="warn" [disabled]="form.invalid" (click)="confirm()">
        <mat-icon>cancel</mat-icon>
        Odbij verifikaciju
      </button>
    </mat-dialog-actions>
  `,
})
export class RejectRenterVerificationDialogComponent {
  form: FormGroup;
  rejectionReasons = REJECTION_REASONS;
  showCustomReason = false;

  constructor(
    private fb: FormBuilder,
    private dialogRef: MatDialogRef<RejectRenterVerificationDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: RejectRenterVerificationDialogData
  ) {
    this.form = this.fb.group({
      reasonType: ['', Validators.required],
      customReason: [''],
      allowResubmission: [true],
    });
  }

  onReasonTypeChange(): void {
    const reasonType = this.form.get('reasonType')?.value;
    this.showCustomReason = reasonType === 'custom';
    
    const customControl = this.form.get('customReason');
    if (this.showCustomReason) {
      customControl?.setValidators([Validators.required, Validators.minLength(10)]);
    } else {
      customControl?.clearValidators();
      customControl?.setValue('');
    }
    customControl?.updateValueAndValidity();
  }

  getReasonText(): string {
    const reasonType = this.form.get('reasonType')?.value;
    
    if (reasonType === 'custom') {
      return this.form.get('customReason')?.value || '';
    }
    
    const found = this.rejectionReasons.find(r => r.value === reasonType);
    return found?.label || reasonType;
  }

  confirm(): void {
    if (this.form.valid) {
      const result: RejectRenterVerificationDialogResult = {
        reason: this.getReasonText(),
        allowResubmission: this.form.get('allowResubmission')?.value ?? true,
      };
      this.dialogRef.close(result);
    }
  }
}