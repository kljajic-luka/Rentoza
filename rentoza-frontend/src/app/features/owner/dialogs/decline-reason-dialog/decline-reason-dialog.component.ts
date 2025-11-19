import { Component, Inject, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatRadioModule } from '@angular/material/radio';

import { Booking } from '@core/models/booking.model';

export interface DeclineReasonDialogData {
  booking: Booking;
}

/**
 * Decline Reason Dialog - Host Approval Workflow (Phase 3)
 * 
 * Purpose:
 * - Collect decline reason from owner when rejecting booking request
 * - Show reason to guest in notification and booking history
 * - Support predefined reasons + custom reason
 * 
 * UX:
 * - Radio buttons for common reasons
 * - Text area for custom reason
 * - Required field validation
 * - Cancel option (returns null)
 */
@Component({
  selector: 'app-decline-reason-dialog',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatRadioModule,
  ],
  templateUrl: './decline-reason-dialog.component.html',
  styleUrls: ['./decline-reason-dialog.component.scss'],
})
export class DeclineReasonDialogComponent {
  private readonly fb = inject(FormBuilder);
  private readonly dialogRef = inject(MatDialogRef<DeclineReasonDialogComponent>);

  protected readonly declineForm = this.fb.nonNullable.group({
    reasonType: ['dates_conflict', Validators.required],
    customReason: [''],
  });

  protected readonly predefinedReasons = [
    { value: 'dates_conflict', label: 'Datumi su već rezervisani' },
    { value: 'car_maintenance', label: 'Vozilo je u servisu' },
    { value: 'personal_use', label: 'Vozilo mi je potrebno za ličnu upotrebu' },
    { value: 'driver_requirements', label: 'Vozač ne ispunjava uslove' },
    { value: 'other', label: 'Drugi razlog (unesite dole)' },
  ];

  constructor(@Inject(MAT_DIALOG_DATA) public data: DeclineReasonDialogData) {}

  protected isCustomReasonRequired(): boolean {
    return this.declineForm.value.reasonType === 'other';
  }

  protected submit(): void {
    const reasonType = this.declineForm.value.reasonType;
    const customReason = this.declineForm.value.customReason?.trim();

    // Validate custom reason if "other" is selected
    if (reasonType === 'other' && !customReason) {
      return;
    }

    // Get human-readable reason
    let reason: string;
    if (reasonType === 'other' && customReason) {
      reason = customReason;
    } else {
      const selectedReason = this.predefinedReasons.find((r) => r.value === reasonType);
      reason = selectedReason?.label || 'Rezervacija je odbijena';
    }

    this.dialogRef.close(reason);
  }

  protected cancel(): void {
    this.dialogRef.close(null);
  }
}
