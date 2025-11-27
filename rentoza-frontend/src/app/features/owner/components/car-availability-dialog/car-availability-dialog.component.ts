import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  inject,
  Inject,
  OnInit,
} from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatNativeDateModule } from '@angular/material/core';
import { MatCardModule } from '@angular/material/card';
import { forkJoin } from 'rxjs';
import { finalize } from 'rxjs/operators';

import { Car } from '@core/models/car.model';
import { BlockedDate } from '@core/models/blocked-date.model';
import { Booking } from '@core/models/booking.model';
import { AvailabilityService } from '@core/services/availability.service';
import { BookingService } from '@core/services/booking.service';
import { ToastService } from '@core/services/toast.service';

export interface CarAvailabilityDialogData {
  car: Car;
}

@Component({
  selector: 'app-car-availability-dialog',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatDatepickerModule,
    MatNativeDateModule,
    MatCardModule,
  ],
  templateUrl: './car-availability-dialog.component.html',
  styleUrls: ['./car-availability-dialog.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CarAvailabilityDialogComponent implements OnInit {
  private readonly dialogRef = inject(MatDialogRef<CarAvailabilityDialogComponent>);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly fb = inject(FormBuilder);
  private readonly availabilityService = inject(AvailabilityService);
  private readonly bookingService = inject(BookingService);
  private readonly toast = inject(ToastService);

  blockedDates: BlockedDate[] = [];
  bookings: Booking[] = [];
  isLoading = false;

  dateRangeForm: FormGroup;

  // Date picker constraints
  startDateMin: Date = this.normalizeDate(new Date());
  endDateMin: Date = this.addDays(this.startDateMin, 1);

  constructor(@Inject(MAT_DIALOG_DATA) public data: CarAvailabilityDialogData) {
    this.dateRangeForm = this.fb.group({
      startDate: [null, Validators.required],
      endDate: [null, Validators.required],
    });
  }

  ngOnInit(): void {
    this.loadData();
  }

  /**
   * Load bookings and blocked dates in parallel
   */
  private loadData(): void {
    this.isLoading = true;
    this.cdr.markForCheck();

    forkJoin({
      bookings: this.bookingService.getBookingsForCar(this.data.car.id.toString()),
      blockedDates: this.availabilityService.getBlockedDatesForCar(+this.data.car.id),
    })
      .pipe(
        finalize(() => {
          this.isLoading = false;
          this.cdr.markForCheck();
        })
      )
      .subscribe({
        next: ({ bookings, blockedDates }) => {
          this.bookings = bookings;
          this.blockedDates = blockedDates;
          this.cdr.markForCheck();
        },
        error: (error) => {
          console.error('Error loading calendar data:', error);
          this.toast.error('Neuspelo učitavanje podataka kalendara. Pokušajte ponovo.');
        },
      });
  }

  /**
   * Date filter for the start date picker
   * Disables dates that are booked or blocked
   */
  dateFilter = (date: Date | null): boolean => {
    if (!date) {
      return true;
    }

    const dateStr = this.toISODateString(date);

    // Check if date is in any booking
    const isBooked = this.bookings.some((booking) => {
      return this.isDateInRange(dateStr, booking.startDate, booking.endDate);
    });

    // Check if date is in any blocked range
    const isBlocked = this.blockedDates.some((blocked) => {
      return this.isDateInRange(dateStr, blocked.startDate, blocked.endDate);
    });

    // Allow selection only if not booked or blocked
    return !isBooked && !isBlocked;
  };

  /**
   * Date filter for the end date picker
   * Disables dates before start date and dates that are booked or blocked
   */
  endDateFilter = (date: Date | null): boolean => {
    if (!date) {
      return true;
    }

    const start = this.dateRangeForm.get('startDate')?.value;
    if (!start) {
      return false;
    }

    const normalizedEnd = this.normalizeDate(date);
    const normalizedStart = this.normalizeDate(start);

    // End date must be after start date
    if (normalizedEnd <= normalizedStart) {
      return false;
    }

    // Check if date is available
    const dateStr = this.toISODateString(date);
    const isBooked = this.bookings.some((booking) => {
      return this.isDateInRange(dateStr, booking.startDate, booking.endDate);
    });

    const isBlocked = this.blockedDates.some((blocked) => {
      return this.isDateInRange(dateStr, blocked.startDate, blocked.endDate);
    });

    return !isBooked && !isBlocked;
  };

  /**
   * Handle start date selection
   * Automatically adjusts end date minimum and resets end date if needed
   */
  handleStartDateChange(date: Date | null): void {
    if (!date) {
      this.dateRangeForm.get('endDate')?.setValue(null);
      this.endDateMin = this.addDays(this.startDateMin, 1);
      return;
    }

    const normalized = this.normalizeDate(date);
    this.updateEndDateMin(normalized);

    // Reset end date if it's before or equal to the new start date
    const currentEnd = this.dateRangeForm.get('endDate')?.value;
    if (!currentEnd || this.normalizeDate(currentEnd) <= normalized) {
      this.dateRangeForm.get('endDate')?.setValue(null);
    }

    this.cdr.markForCheck();
  }

  /**
   * Handle end date selection
   * Validates that end date is after start date
   */
  handleEndDateChange(date: Date | null): void {
    if (!date) {
      return;
    }

    const start = this.dateRangeForm.get('startDate')?.value;
    if (!start) {
      return;
    }

    const normalizedEnd = this.normalizeDate(date);
    const normalizedStart = this.normalizeDate(start);

    // Ensure end date is after start date
    if (normalizedEnd <= normalizedStart) {
      this.dateRangeForm.get('endDate')?.setValue(null);
      this.toast.warning('Krajnji datum mora biti posle početnog datuma.');
    }

    this.cdr.markForCheck();
  }

  /**
   * Block the selected date range
   */
  blockDates(): void {
    if (this.dateRangeForm.invalid) {
      this.toast.validationError('Molimo izaberite početni i krajnji datum.');
      return;
    }

    const { startDate, endDate } = this.dateRangeForm.value;

    if (endDate < startDate) {
      this.toast.warning('Krajnji datum mora biti posle početnog datuma.');
      return;
    }

    this.isLoading = true;
    this.cdr.markForCheck();

    this.availabilityService
      .blockDateRange({
        carId: +this.data.car.id,
        startDate: this.toISODateString(startDate),
        endDate: this.toISODateString(endDate),
      })
      .pipe(
        finalize(() => {
          this.isLoading = false;
          this.cdr.markForCheck();
        })
      )
      .subscribe({
        next: () => {
          this.toast.success('Datumi su uspešno blokirani.');
          this.availabilityService.clearCache(+this.data.car.id);
          this.dateRangeForm.reset();
          this.loadData();
        },
        error: (error) => {
          console.error('Error blocking dates:', error);
          const message = error.error?.error || 'Neuspelo blokiranje datuma. Pokušajte ponovo.';
          this.toast.error(message);
        },
      });
  }

  /**
   * Unblock a specific blocked date range
   * Shows confirmation dialog and provides clear feedback
   */
  unblockDates(block: BlockedDate): void {
    // Show confirmation dialog
    const confirmed = confirm(
      `Da li ste sigurni da želite da odblokirate datume od ${this.formatDate(
        block.startDate
      )} do ${this.formatDate(block.endDate)}?`
    );

    if (!confirmed) {
      return;
    }

    this.isLoading = true;
    this.cdr.markForCheck();

    this.availabilityService
      .unblockDateRange(block.id)
      .pipe(
        finalize(() => {
          this.isLoading = false;
          this.cdr.markForCheck();
        })
      )
      .subscribe({
        next: () => {
          // Show success toast with updated message
          this.toast.success('Datumi su uspešno odblokirani.');
          this.availabilityService.clearCache(+this.data.car.id);
          this.loadData();
        },
        error: (error) => {
          console.error('Error unblocking dates:', error);
          // Show error toast with clear message
          this.toast.error('Greška prilikom odblokiranja datuma. Pokušajte ponovo.');
        },
      });
  }

  /**
   * Check if a date falls within a range (inclusive)
   */
  private isDateInRange(dateStr: string, startStr: string, endStr: string): boolean {
    return dateStr >= startStr && dateStr <= endStr;
  }

  /**
   * Convert Date to ISO date string (YYYY-MM-DD)
   */
  private toISODateString(date: Date): string {
    return date.toISOString().split('T')[0];
  }

  /**
   * Format ISO date string to display format
   */
  formatDate(dateStr: string): string {
    const date = new Date(dateStr);
    return date.toLocaleDateString('sr-RS', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
    });
  }

  /**
   * Update the minimum allowed end date based on start date
   */
  private updateEndDateMin(startDate: Date): void {
    this.endDateMin = this.addDays(startDate, 1);
  }

  /**
   * Normalize a date to remove time component
   */
  private normalizeDate(value: Date | string): Date {
    const date = new Date(value);
    date.setHours(0, 0, 0, 0);
    return date;
  }

  /**
   * Add days to a date
   */
  private addDays(date: Date, days: number): Date {
    const result = new Date(date);
    result.setDate(result.getDate() + days);
    return result;
  }

  close(): void {
    this.dialogRef.close();
  }
}
