import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';

export interface RejectDialogData {
  displayName: string;
}

/**
 * Predefined rejection reasons.
 */
const REJECTION_REASONS = [
  { value: 'BLURRY_IMAGE', label: 'Nejasna slika - nije moguće pročitati podatke' },
  { value: 'EXPIRED_LICENSE', label: 'Vozačka dozvola je istekla' },
  { value: 'INVALID_DOCUMENT', label: 'Priloženi dokument nije vozačka dozvola' },
  { value: 'INCOMPLETE_UPLOAD', label: 'Nedostaje prednja ili zadnja strana' },
  { value: 'NAME_MISMATCH', label: 'Ime na dozvoli ne odgovara imenu na nalogu' },
  { value: 'SUSPECTED_FRAUD', label: 'Sumnja na falsifikat ili manipulaciju' },
  { value: 'OTHER', label: 'Drugi razlog (navedite)' },
];

/**
 * Dialog for rejecting a renter verification with a reason.
 */
@Component({
  selector: 'app-reject-renter-verification-dialog',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatIconModule,
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon color="warn">block</mat-icon>
      Odbijanje verifikacije
    </h2>

    <mat-dialog-content>
      <p class="dialog-description">
        Da li ste sigurni da želite da odbijete verifikaciju za
        <strong>{{ data.displayName }}</strong
        >?
      </p>

      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Razlog odbijanja</mat-label>
        <mat-select [formControl]="selectedReason">
          @for (reason of rejectionReasons; track reason.value) {
          <mat-option [value]="reason.value">
            {{ reason.label }}
          </mat-option>
          }
        </mat-select>
      </mat-form-field>

      @if (selectedReason.value === 'OTHER') {
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Dodatno objašnjenje</mat-label>
        <textarea
          matInput
          [formControl]="customReason"
          rows="3"
          placeholder="Unesite razlog odbijanja..."
        ></textarea>
        <mat-error *ngIf="customReason.hasError('required')"> Razlog je obavezan </mat-error>
        <mat-error *ngIf="customReason.hasError('minlength')">
          Razlog mora imati najmanje 10 karaktera
        </mat-error>
      </mat-form-field>
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Otkaži</button>
      <button mat-raised-button color="warn" [disabled]="!isValid()" (click)="onReject()">
        <mat-icon>block</mat-icon>
        Odbij verifikaciju
      </button>
    </mat-dialog-actions>
  `,
  styles: [
    `
      h2[mat-dialog-title] {
        display: flex;
        align-items: center;
        gap: 8px;

        mat-icon {
          font-size: 28px;
          width: 28px;
          height: 28px;
        }
      }

      .dialog-description {
        margin-bottom: 16px;
        color: var(--text-secondary, #666);
      }

      .full-width {
        width: 100%;
        margin-bottom: 8px;
      }

      mat-dialog-actions {
        gap: 8px;

        button mat-icon {
          margin-right: 8px;
        }
      }
    `,
  ],
})
export class RejectRenterVerificationDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<RejectRenterVerificationDialogComponent>);
  readonly data: RejectDialogData = inject(MAT_DIALOG_DATA);

  readonly rejectionReasons = REJECTION_REASONS;

  readonly selectedReason = new FormControl<string>('', Validators.required);
  readonly customReason = new FormControl<string>('', [
    Validators.required,
    Validators.minLength(10),
  ]);

  isValid(): boolean {
    if (!this.selectedReason.valid) return false;
    if (this.selectedReason.value === 'OTHER' && !this.customReason.valid) return false;
    return true;
  }

  onReject(): void {
    if (!this.isValid()) return;

    let reason: string;
    if (this.selectedReason.value === 'OTHER') {
      reason = this.customReason.value || '';
    } else {
      // Find the label for the selected reason
      const found = REJECTION_REASONS.find((r) => r.value === this.selectedReason.value);
      reason = found?.label || this.selectedReason.value || '';
    }

    this.dialogRef.close(reason);
  }
}
