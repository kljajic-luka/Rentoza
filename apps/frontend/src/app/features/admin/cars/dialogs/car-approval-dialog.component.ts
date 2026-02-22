import { Component, Inject, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { AdminCarDto, AdminApiService } from '@core/services/admin-api.service';
import { ApprovalStatus } from '@core/models/car.model';

@Component({
  selector: 'app-car-approval-dialog',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatSelectModule,
  ],
  template: `
    <h2 mat-dialog-title>Pregled Vozila: {{ data.car.brand }} {{ data.car.model }}</h2>

    <mat-dialog-content>
      <div class="car-summary">
        <div class="info-row">
          <span class="label">Vlasnik:</span>
          <span class="value">{{ data.car.ownerEmail }}</span>
        </div>
        <div class="info-row">
          <span class="label">Godište:</span>
          <span class="value">{{ data.car.year }}</span>
        </div>
        <div class="info-row">
          <span class="label">Status:</span>
          <span class="badge" [ngClass]="getStatusClass(data.car.approvalStatus)">
            {{ data.car.approvalStatus || 'PENDING' }}
          </span>
        </div>
      </div>

      <form [formGroup]="form" class="action-form">
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Akcija</mat-label>
          <mat-select formControlName="action">
            <mat-option value="APPROVE">Odobri</mat-option>
            <mat-option value="REJECT">Odbij</mat-option>
            <mat-option value="SUSPEND">Suspenduj</mat-option>
          </mat-select>
        </mat-form-field>

        <mat-form-field
          appearance="outline"
          class="full-width"
          *ngIf="form.get('action')?.value !== 'APPROVE'"
        >
          <mat-label>Razlog (Obavezno za odbijanje/suspenziju)</mat-label>
          <textarea matInput formControlName="reason" rows="3"></textarea>
          <mat-error *ngIf="form.get('reason')?.hasError('required')">
            Razlog je obavezan
          </mat-error>
        </mat-form-field>
      </form>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Odustani</button>
      <button
        mat-flat-button
        color="primary"
        (click)="submit()"
        [disabled]="form.invalid || loading"
      >
        {{ getSubmitLabel() }}
      </button>
    </mat-dialog-actions>
  `,
  styles: [
    `
      .full-width {
        width: 100%;
        margin-bottom: 16px;
      }
      .car-summary {
        background: var(--color-surface-muted, #f5f5f5);
        padding: 16px;
        border-radius: 8px;
        margin-bottom: 24px;
        border: 1px solid var(--color-border-subtle, transparent);
      }
      .info-row {
        display: flex;
        justify-content: space-between;
        margin-bottom: 8px;
      }
      .label {
        font-weight: 500;
        color: var(--admin-muted, #666);
      }
      .value {
        color: var(--admin-strong, #000);
      }
      .badge {
        padding: 4px 8px;
        border-radius: 4px;
        font-size: 12px;
        font-weight: 500;
        border: 1px solid transparent;
      }
      .badge-success {
        background: rgba(16, 185, 129, 0.12);
        color: #10b981;
        border-color: rgba(16, 185, 129, 0.2);
      }
      .badge-warning {
        background: rgba(249, 115, 22, 0.12);
        color: #f97316;
        border-color: rgba(249, 115, 22, 0.2);
      }
      .badge-error {
        background: rgba(239, 68, 68, 0.12);
        color: #ef4444;
        border-color: rgba(239, 68, 68, 0.2);
      }
    `,
  ],
})
export class CarApprovalDialogComponent {
  private fb = inject(FormBuilder);
  private adminApi = inject(AdminApiService);
  private dialogRef = inject(MatDialogRef<CarApprovalDialogComponent>);

  loading = false;

  form = this.fb.group({
    action: ['APPROVE', Validators.required],
    reason: [''],
  });

  constructor(@Inject(MAT_DIALOG_DATA) public data: { car: AdminCarDto }) {
    // Update validators based on action
    this.form.get('action')?.valueChanges.subscribe((action) => {
      const reasonControl = this.form.get('reason');
      if (action === 'APPROVE') {
        reasonControl?.clearValidators();
      } else {
        reasonControl?.setValidators([Validators.required, Validators.minLength(10)]);
      }
      reasonControl?.updateValueAndValidity();
    });
  }

  getStatusClass(status?: string): string {
    switch (status) {
      case ApprovalStatus.APPROVED:
        return 'badge-success';
      case ApprovalStatus.PENDING:
        return 'badge-warning';
      case ApprovalStatus.REJECTED:
        return 'badge-error';
      default:
        return 'badge-warning';
    }
  }

  getSubmitLabel(): string {
    const action = this.form.get('action')?.value;
    if (this.loading) return 'Obrada...';
    switch (action) {
      case 'APPROVE':
        return 'Odobri Vozilo';
      case 'REJECT':
        return 'Odbij Vozilo';
      case 'SUSPEND':
        return 'Suspenduj Vozilo';
      default:
        return 'Potvrdi';
    }
  }

  submit(): void {
    if (this.form.invalid) return;

    const { action, reason } = this.form.value;

    // Confirmation for destructive actions (reject/suspend)
    if (action !== 'APPROVE') {
      const confirmMsg =
        action === 'REJECT'
          ? 'Da li ste sigurni da želite da odbijete ovo vozilo? Vlasnik će biti obavešten.'
          : 'Da li ste sigurni da želite da suspendujete ovo vozilo? Vozilo će biti uklonjeno iz pretrage.';

      if (!confirm(confirmMsg)) {
        return;
      }
    }

    this.loading = true;
    const carId = this.data.car.id;
    let request;

    switch (action) {
      case 'APPROVE':
        request = this.adminApi.approveCar(carId);
        break;
      case 'REJECT':
        request = this.adminApi.rejectCar(carId, reason || '');
        break;
      case 'SUSPEND':
        request = this.adminApi.suspendCar(carId, reason || '');
        break;
    }

    if (request) {
      request.subscribe({
        next: () => {
          this.dialogRef.close(true);
        },
        error: (err) => {
          console.error('Action failed', err);
          this.loading = false;
        },
      });
    }
  }
}