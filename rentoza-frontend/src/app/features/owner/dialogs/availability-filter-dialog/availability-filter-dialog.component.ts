import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import {
  ReactiveFormsModule,
  FormGroup,
  FormControl,
  Validators,
  AbstractControl,
  ValidationErrors,
} from '@angular/forms';

@Component({
  selector: 'app-availability-filter-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatDatepickerModule,
    MatNativeDateModule,
    ReactiveFormsModule,
  ],
  template: `
    <h2 mat-dialog-title>Proveri Dostupnost</h2>
    <mat-dialog-content>
      <form [formGroup]="filterForm" class="filter-form">
        <mat-form-field appearance="fill">
          <mat-label>Unesite period</mat-label>
          <mat-date-range-input [rangePicker]="picker" [min]="minDate">
            <input matStartDate formControlName="start" placeholder="Datum preuzimanja" />
            <input matEndDate formControlName="end" placeholder="Datum vraćanja" />
          </mat-date-range-input>
          <mat-datepicker-toggle matIconSuffix [for]="picker"></mat-datepicker-toggle>
          <mat-date-range-picker #picker></mat-date-range-picker>
          <mat-error *ngIf="filterForm.controls['start'].hasError('required')"
            >Datum preuzimanja je obavezan</mat-error
          >
          <mat-error *ngIf="filterForm.controls['end'].hasError('required')"
            >Datum vraćanja je obavezan</mat-error
          >
        </mat-form-field>

        <div class="time-inputs">
          <mat-form-field appearance="fill">
            <mat-label>Vreme preuzimanja</mat-label>
            <input matInput type="time" formControlName="startTime" />
            <mat-error *ngIf="filterForm.controls['startTime'].hasError('required')"
              >Obavezno</mat-error
            >
          </mat-form-field>
          <mat-form-field appearance="fill">
            <mat-label>Vreme vraćanja</mat-label>
            <input matInput type="time" formControlName="endTime" />
            <mat-error *ngIf="filterForm.controls['endTime'].hasError('required')"
              >Obavezno</mat-error
            >
          </mat-form-field>
        </div>
        <mat-error
          *ngIf="
            filterForm.hasError('invalidTimeRange') && (filterForm.touched || filterForm.dirty)
          "
          class="error-message"
        >
          Vreme vraćanja mora biti posle vremena preuzimanja
        </mat-error>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Otkaži</button>
      <button
        mat-raised-button
        color="primary"
        [disabled]="filterForm.invalid"
        (click)="applyFilter()"
      >
        Primeni filter
      </button>
    </mat-dialog-actions>
  `,
  styles: [
    `
      .filter-form {
        display: flex;
        flex-direction: column;
        gap: 16px;
        min-width: 300px;
        padding-top: 10px;
      }
      .time-inputs {
        display: flex;
        gap: 16px;
      }
      .error-message {
        color: #f44336;
        font-size: 75%;
        margin-top: -10px;
        margin-left: 4px;
      }
    `,
  ],
})
export class AvailabilityFilterDialogComponent {
  private dialogRef = inject(MatDialogRef<AvailabilityFilterDialogComponent>);

  minDate = new Date();

  filterForm = new FormGroup(
    {
      start: new FormControl<Date | null>(null, Validators.required),
      end: new FormControl<Date | null>(null, Validators.required),
      startTime: new FormControl<string>('10:00', Validators.required),
      endTime: new FormControl<string>('10:00', Validators.required),
    },
    { validators: this.dateRangeValidator }
  );

  dateRangeValidator(group: AbstractControl): ValidationErrors | null {
    const start = group.get('start')?.value;
    const end = group.get('end')?.value;
    const startTime = group.get('startTime')?.value;
    const endTime = group.get('endTime')?.value;

    if (!start || !end || !startTime || !endTime) {
      return null;
    }

    const startDateTime = new Date(start);
    const [startHour, startMinute] = startTime.split(':').map(Number);
    startDateTime.setHours(startHour, startMinute);

    const endDateTime = new Date(end);
    const [endHour, endMinute] = endTime.split(':').map(Number);
    endDateTime.setHours(endHour, endMinute);

    if (endDateTime <= startDateTime) {
      return { invalidTimeRange: true };
    }
    return null;
  }

  applyFilter() {
    if (this.filterForm.valid) {
      const val = this.filterForm.value;
      const start = val.start!;
      const end = val.end!;

      // Combine date and time
      const startTimeParts = val.startTime!.split(':');
      const endTimeParts = val.endTime!.split(':');

      const startDateTime = new Date(start);
      startDateTime.setHours(Number(startTimeParts[0]), Number(startTimeParts[1]));

      const endDateTime = new Date(end);
      endDateTime.setHours(Number(endTimeParts[0]), Number(endTimeParts[1]));

      this.dialogRef.close({ start: startDateTime, end: endDateTime });
    }
  }
}
