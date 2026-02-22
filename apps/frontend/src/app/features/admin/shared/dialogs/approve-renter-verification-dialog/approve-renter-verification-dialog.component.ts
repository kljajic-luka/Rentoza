import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { ReactiveFormsModule, FormBuilder, FormGroup } from '@angular/forms';

import { RenterVerificationProfileDto } from '../../../../../core/models/admin-renter-verification.model';

export interface ApproveRenterVerificationDialogData {
  profile: RenterVerificationProfileDto;
}

export interface ApproveRenterVerificationDialogResult {
  notes?: string;
}

/**
 * Approval Confirmation Dialog
 *
 * Shows verification summary and optional notes field.
 */
@Component({
  selector: 'app-approve-renter-verification-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    ReactiveFormsModule,
  ],
  styleUrls: ['../../../admin-shared.styles.scss'],
  styles: [
    `
      .approval-dialog {
        max-width: 480px;
      }

      .user-info {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 16px;
        background: var(--color-surface-muted);
        border-radius: 12px;
        margin-bottom: 16px;
      }

      .user-avatar {
        width: 48px;
        height: 48px;
        border-radius: 12px;
        background: linear-gradient(135deg, var(--brand-primary), var(--color-primary-hover));
        color: white;
        display: flex;
        align-items: center;
        justify-content: center;
        font-weight: 700;
        font-size: 18px;
      }

      .user-name {
        font-weight: 600;
        font-size: 16px;
      }
      .user-email {
        font-size: 13px;
        color: var(--admin-muted);
      }

      .documents-summary {
        display: flex;
        flex-direction: column;
        gap: 8px;
        margin-bottom: 16px;
      }

      .doc-check {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 13px;
        color: var(--admin-strong);

        mat-icon {
          font-size: 18px;
          width: 18px;
          height: 18px;
          color: #10b981;
        }
      }

      mat-form-field {
        width: 100%;
      }

      mat-dialog-actions {
        gap: 12px;
      }
    `,
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon style="color: #10b981; margin-right: 8px;">verified</mat-icon>
      Odobri verifikaciju
    </h2>

    <mat-dialog-content class="approval-dialog">
      <p class="dialog-copy">
        Da li ste sigurni da želite da odobrite verifikaciju vozačke dozvole za ovog korisnika?
      </p>

      <div class="user-info" *ngIf="data.profile">
        <div class="user-avatar">
          {{ getInitials() }}
        </div>
        <div>
          <div class="user-name">{{ data.profile.fullName }}</div>
          <div class="user-email">{{ data.profile.email }}</div>
        </div>
      </div>

      <div class="documents-summary" *ngIf="data.profile?.documents">
        <h4
          style="margin: 0 0 8px; font-size: 12px; text-transform: uppercase; color: var(--admin-muted);"
        >
          Dokumenti za odobrenje
        </h4>
        <div class="doc-check" *ngFor="let doc of data.profile.documents">
          <mat-icon>check_circle</mat-icon>
          <span>{{ doc.typeDisplay }}</span>
          <span style="color: var(--admin-muted);" *ngIf="doc.ocrConfidencePercent !== undefined">
            ({{ doc.ocrConfidencePercent }}% OCR)
          </span>
          <span style="color: var(--admin-muted);" *ngIf="doc.faceMatchPercent !== undefined">
            ({{ doc.faceMatchPercent }}% podudarnost)
          </span>
        </div>
      </div>

      <form [formGroup]="form">
        <mat-form-field appearance="outline">
          <mat-label>Beleške (opciono)</mat-label>
          <textarea
            matInput
            formControlName="notes"
            rows="2"
            placeholder="npr. Svi dokumenti su provereni i u redu."
          ></textarea>
        </mat-form-field>
      </form>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Otkaži</button>
      <button mat-flat-button color="primary" (click)="confirm()">
        <mat-icon>check_circle</mat-icon>
        Potvrdi odobrenje
      </button>
    </mat-dialog-actions>
  `,
})
export class ApproveRenterVerificationDialogComponent {
  form: FormGroup;

  constructor(
    private fb: FormBuilder,
    private dialogRef: MatDialogRef<ApproveRenterVerificationDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: ApproveRenterVerificationDialogData,
  ) {
    this.form = this.fb.group({
      notes: [''],
    });
  }

  getInitials(): string {
    if (!this.data.profile?.fullName) return '?';
    const parts = this.data.profile.fullName.split(' ');
    return parts
      .map((p) => p[0]?.toUpperCase() || '')
      .slice(0, 2)
      .join('');
  }

  confirm(): void {
    const result: ApproveRenterVerificationDialogResult = {
      notes: this.form.value.notes || undefined,
    };
    this.dialogRef.close(result);
  }
}
