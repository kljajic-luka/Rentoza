import {
  Component,
  Inject,
  OnInit,
  inject,
  signal,
  DestroyRef,
  ChangeDetectorRef,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatRadioModule } from '@angular/material/radio';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { Car } from '@core/models/car.model';
import { BookingService } from '@core/services/booking.service';
import { AuthService } from '@core/auth/auth.service';
import { take } from 'rxjs';
import { Booking } from '@core/models/booking.model';
import { BlockedDate } from '@core/models/blocked-date.model';

export interface BookingDialogData {
  car: Car;
  bookings: Booking[];
  blockedDates: BlockedDate[];
}

@Component({
  selector: 'app-booking-dialog',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatRadioModule,
    MatCheckboxModule,
    MatDatepickerModule,
    MatIconModule,
    MatDividerModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
  ],
  templateUrl: './booking-dialog.component.html',
  styleUrls: ['./booking-dialog.component.scss'],
})
export class BookingDialogComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly bookingService = inject(BookingService);
  private readonly authService = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialogRef = inject(MatDialogRef<BookingDialogComponent>);
  private readonly destroyRef = inject(DestroyRef);
  private readonly cdr = inject(ChangeDetectorRef);

  // Phase 2.1: Booking constants for date filtering
  private readonly BUFFER_DAYS = 1;
  private readonly MAX_RENTAL_DAYS = 30;
  private readonly MAX_ADVANCE_BOOKING_DAYS = 365;

  protected readonly isSubmitting = signal(false);
  protected readonly totalPrice = signal(0);
  protected readonly basePrice = signal(0);
  protected readonly insuranceCost = signal(0);
  protected readonly refuelCost = signal(0);
  protected readonly rentalDays = signal(0);

  protected readonly startDateMin = new Date();
  protected endDateMin = new Date();

  protected readonly bookingForm = this.fb.nonNullable.group({
    startDate: [null as Date | null, Validators.required],
    endDate: [null as Date | null, Validators.required],
    driverName: [''], // Read-only, prefilled from user profile
    driverSurname: [''], // Read-only, prefilled from user profile
    driverAge: [null as number | null], // Read-only, prefilled from user profile
    driverPhone: [''], // Read-only, prefilled from user profile
    insuranceType: ['BASIC', Validators.required],
    prepaidRefuel: [false],
    pickupTimeWindow: [
      'MORNING' as 'MORNING' | 'AFTERNOON' | 'EVENING' | 'EXACT',
      Validators.required,
    ], // Phase 2.2
    pickupTime: [null as string | null], // Phase 2.2: Conditionally required
  });

  constructor(@Inject(MAT_DIALOG_DATA) public data: BookingDialogData) {}

  ngOnInit(): void {
    // Prefill driver details from current user and disable for read-only behavior
    this.authService.currentUser$.pipe(take(1)).subscribe((user) => {
      if (user) {
        // 1️⃣ Patch user data first (before disabling)
        this.bookingForm.patchValue({
          driverName: user.firstName || '',
          driverSurname: user.lastName || '',
          driverAge: user.age || null,
          driverPhone: user.phone || '',
        });

        // 2️⃣ Disable driver fields to enforce read-only (prevents typing, keeps visible data)
        this.bookingForm.get('driverName')?.disable({ emitEvent: false });
        this.bookingForm.get('driverSurname')?.disable({ emitEvent: false });
        this.bookingForm.get('driverAge')?.disable({ emitEvent: false });
        this.bookingForm.get('driverPhone')?.disable({ emitEvent: false });

        // 3️⃣ Force view refresh so Angular Material updates the input displays
        this.cdr.detectChanges();
      } else {
        console.warn('⚠️ currentUser$ emitted null or incomplete user');
      }
    });

    // Recalculate price whenever form values change
    this.bookingForm.valueChanges.subscribe(() => {
      this.calculatePrice();
    });

    // Phase 2.2: Conditional validation for pickupTime
    this.bookingForm.get('pickupTimeWindow')?.valueChanges.subscribe((window) => {
      const pickupTimeControl = this.bookingForm.get('pickupTime');
      if (window === 'EXACT') {
        pickupTimeControl?.setValidators([Validators.required]);
      } else {
        pickupTimeControl?.clearValidators();
        pickupTimeControl?.setValue(null);
      }
      pickupTimeControl?.updateValueAndValidity();
    });

    // Phase 2.1 Regression Fix: Reactive refresh for end date when start date changes
    this.bookingForm
      .get('startDate')
      ?.valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.bookingForm.get('endDate')?.updateValueAndValidity();
      });
  }

  /**
   * Phase 2.1 Regression Fix: Self-contained start date filter
   * Filters out unavailable dates and dates in the past
   */
  protected readonly startDateFilter = (date: Date | null): boolean => {
    if (!date) {
      return false;
    }
    const normalized = this.normalizeDate(date);
    const today = this.normalizeDate(new Date());
    return normalized >= today && !this.isDateUnavailable(normalized);
  };

  /**
   * Phase 2.1 Regression Fix: Self-contained end date filter
   *
   * This filter is now computed based on the dialog's OWN form state,
   * not passed from parent. This fixes the context binding issue where
   * filter functions lost access to 'this'.
   *
   * Applies 5 validation rules:
   * 1. Minimum rental (1 day)
   * 2. Maximum rental (30 days)
   * 3. Advance booking limit (12 months)
   * 4. Stop at next unavailable date (buffer-aware)
   * 5. Basic date validation
   */
  protected readonly endDateFilter = (date: Date | null): boolean => {
    if (!date) {
      return false;
    }
    const start = this.bookingForm.controls.startDate.value;
    if (!start) {
      return false;
    }

    const normalizedEnd = this.normalizeDate(date);
    const normalizedStart = this.normalizeDate(start);

    // 1. Enforce minimum rental (1 day)
    if (normalizedEnd <= this.addDays(normalizedStart, 0)) {
      return false;
    }

    // 2. Enforce maximum rental duration (30 days)
    if (normalizedEnd > this.addDays(normalizedStart, this.MAX_RENTAL_DAYS)) {
      return false;
    }

    // 3. Enforce advance booking limit (≤ 12 months)
    const maxAdvance = this.addDays(this.normalizeDate(new Date()), this.MAX_ADVANCE_BOOKING_DAYS);
    if (normalizedEnd > maxAdvance) {
      return false;
    }

    // 4. Compute next unavailable date AFTER start + 1 day (permissive UI filtering)
    const nextBlocked = this.getNextUnavailableDate(this.addDays(normalizedStart, 1));

    // 5. Permit all dates before the next blocked segment (exclusive)
    if (nextBlocked && normalizedEnd >= nextBlocked) {
      return false;
    }

    return true;
  };

  protected handleStartDateChange(date: Date | null): void {
    if (!date) return;

    // Update minimum end date to be one day after start date
    const minEnd = new Date(date);
    minEnd.setDate(minEnd.getDate() + 1);
    this.endDateMin = minEnd;

    // Clear end date if it's now invalid
    const endDate = this.bookingForm.value.endDate;
    if (endDate && endDate <= date) {
      this.bookingForm.patchValue({ endDate: null });
    }

    this.calculatePrice();
  }

  protected handleEndDateChange(date: Date | null): void {
    if (!date) return;
    this.calculatePrice();
  }

  private calculatePrice(): void {
    const start = this.bookingForm.value.startDate;
    const end = this.bookingForm.value.endDate;
    const insuranceType = this.bookingForm.value.insuranceType || 'BASIC';
    const prepaidRefuel = this.bookingForm.value.prepaidRefuel || false;

    if (!start || !end) {
      this.resetPriceCalculation();
      return;
    }

    // Calculate days
    const days = Math.ceil((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24));
    this.rentalDays.set(days);

    // Base price
    const base = days * this.data.car.pricePerDay;
    this.basePrice.set(base);

    // Insurance cost
    const insuranceMultiplier = this.getInsuranceMultiplier(insuranceType);
    const insurance = base * (insuranceMultiplier - 1);
    this.insuranceCost.set(insurance);

    // Refuel cost
    let refuel = 0;
    if (prepaidRefuel && this.data.car.fuelConsumption) {
      refuel = this.data.car.fuelConsumption * 6.5 * 10;
    }
    this.refuelCost.set(refuel);

    // Total
    const total = base * insuranceMultiplier + refuel;
    this.totalPrice.set(total);
  }

  private getInsuranceMultiplier(type: string): number {
    switch (type?.toUpperCase()) {
      case 'STANDARD':
        return 1.1;
      case 'PREMIUM':
        return 1.2;
      default:
        return 1.0;
    }
  }

  private resetPriceCalculation(): void {
    this.rentalDays.set(0);
    this.basePrice.set(0);
    this.insuranceCost.set(0);
    this.refuelCost.set(0);
    this.totalPrice.set(0);
  }

  protected submitBooking(): void {
    if (this.bookingForm.invalid) {
      this.snackBar.open('Molimo popunite sva obavezna polja', 'Zatvori', { duration: 3000 });
      return;
    }

    // Get all form values including disabled controls (driver fields)
    const formValues = this.bookingForm.getRawValue();

    // Validate driver information is present (should be prefilled from profile)
    const { driverName, driverSurname, driverAge, driverPhone } = formValues;

    if (!driverName || !driverSurname || !driverAge || !driverPhone) {
      this.snackBar.open(
        'Podaci vozača nisu kompletni. Molimo ažurirajte svoj profil.',
        'Zatvori',
        {
          duration: 5000,
          panelClass: ['snackbar-error'],
        }
      );
      return;
    }

    if (driverAge < 18) {
      this.snackBar.open('Morate imati najmanje 18 godina da biste iznajmili vozilo', 'Zatvori', {
        duration: 4000,
        panelClass: ['snackbar-error'],
      });
      return;
    }

    this.isSubmitting.set(true);

    const payload = {
      carId: this.data.car.id.toString(),
      startDate: formValues.startDate!.toISOString(),
      endDate: formValues.endDate!.toISOString(),
      driverName: formValues.driverName,
      driverSurname: formValues.driverSurname,
      driverAge: formValues.driverAge,
      driverPhone: formValues.driverPhone,
      insuranceType: formValues.insuranceType || 'BASIC',
      prepaidRefuel: formValues.prepaidRefuel || false,
      pickupTimeWindow: formValues.pickupTimeWindow || 'MORNING', // Phase 2.2
      pickupTime: formValues.pickupTime || undefined, // Phase 2.2: Only if EXACT
    };

    // Phase 2.3: Pre-submit validation - check for conflicts before creating booking
    this.bookingService.validateBooking(payload).subscribe({
      next: (result) => {
        if (result.available) {
          // Dates are available, proceed with booking creation
          this.createBookingConfirmed(payload);
        } else {
          // Dates not available (shouldn't reach here if 409 is returned, but handle just in case)
          this.snackBar.open(
            'Ovi datumi su upravo rezervisani. Molimo izaberite druge datume.',
            'Zatvori',
            {
              duration: 5000,
              panelClass: ['snackbar-error'],
            }
          );
          this.isSubmitting.set(false);
        }
      },
      error: (error) => {
        // Handle 409 Conflict - dates were taken by another user
        if (error.status === 409) {
          this.snackBar.open(
            'Ovi datumi su upravo rezervisani. Molimo izaberite druge datume.',
            'Zatvori',
            {
              duration: 5000,
              panelClass: ['snackbar-error'],
            }
          );
          this.isSubmitting.set(false);
        } else {
          // Other validation errors
          console.error('Validation error:', error);
          const errorMessage =
            error.error?.message || 'Greška pri proveri dostupnosti. Pokušajte ponovo.';
          this.snackBar.open(errorMessage, 'Zatvori', {
            duration: 5000,
            panelClass: ['snackbar-error'],
          });
          this.isSubmitting.set(false);
        }
      },
    });
  }

  /**
   * Phase 2.3: Create booking after validation passes
   * Phase 3: Updated for host approval workflow
   * 
   * Behavior depends on backend feature flag (app.booking.host-approval.enabled):
   * - If enabled: Status = PENDING_APPROVAL, awaiting host decision
   * - If disabled: Status = ACTIVE, instant confirmation (legacy)
   * 
   * Success message updated to reflect approval workflow.
   */
  private createBookingConfirmed(payload: any): void {
    this.bookingService.createBooking(payload).subscribe({
      next: (booking) => {
        // Phase 3: Check if booking requires approval or is instant
        if (booking.status === 'PENDING_APPROVAL') {
          this.snackBar.open(
            'Vaš zahtev za rezervaciju je poslat! Čekamo odobrenje domaćina.',
            'Zatvori',
            {
              duration: 5000,
              panelClass: ['snackbar-info'],
            }
          );
        } else {
          // Legacy instant booking (ACTIVE)
          this.snackBar.open(
            'Vaša rezervacija je uspešno potvrđena!',
            'Zatvori',
            {
              duration: 4000,
              panelClass: ['snackbar-success'],
            }
          );
        }
        this.dialogRef.close(true);
      },
      error: (error) => {
        console.error('Booking error:', error);
        const errorMessage =
          error.error?.message || 'Greška pri kreiranju rezervacije. Pokušajte ponovo.';
        this.snackBar.open(errorMessage, 'Zatvori', {
          duration: 5000,
          panelClass: ['snackbar-error'],
        });
        this.isSubmitting.set(false);
      },
    });
  }

  protected cancel(): void {
    this.dialogRef.close(false);
  }

  // ========== Phase 2.1 Date Filtering Helper Methods ==========

  /**
   * Check if a specific date is unavailable (booked or blocked, including buffer days)
   */
  private isDateUnavailable(date: Date): boolean {
    const normalized = this.normalizeDate(date);

    // Check if date is in any booking (with buffer days)
    const isBooked = this.data.bookings.some((booking) =>
      this.isDateWithinRange(
        normalized,
        this.addDays(this.normalizeDate(booking.startDate), -this.BUFFER_DAYS),
        this.addDays(this.normalizeDate(booking.endDate), this.BUFFER_DAYS)
      )
    );

    // Check if date is in any blocked range (with buffer days)
    const isBlocked = this.data.blockedDates.some((blocked) =>
      this.isDateWithinRange(
        normalized,
        this.addDays(this.normalizeDate(blocked.startDate), -this.BUFFER_DAYS),
        this.addDays(this.normalizeDate(blocked.endDate), this.BUFFER_DAYS)
      )
    );

    return isBooked || isBlocked;
  }

  /**
   * Find the next unavailable date AFTER a given date (including buffer days)
   *
   * Phase 2.1 Regression Fix: Filters out ranges that END before 'from' date,
   * ensuring we only return truly future blocked segments.
   */
  private getNextUnavailableDate(from: Date): Date | null {
    const ranges = [
      ...this.data.bookings.map((b) => ({
        start: this.addDays(this.normalizeDate(b.startDate), -this.BUFFER_DAYS),
        end: this.addDays(this.normalizeDate(b.endDate), this.BUFFER_DAYS),
      })),
      ...this.data.blockedDates.map((b) => ({
        start: this.addDays(this.normalizeDate(b.startDate), -this.BUFFER_DAYS),
        end: this.addDays(this.normalizeDate(b.endDate), this.BUFFER_DAYS),
      })),
    ]
      .filter((r) => r.end > from) // Ignore past ranges that end before 'from'
      .sort((a, b) => a.start.getTime() - b.start.getTime());

    return ranges.length > 0 ? ranges[0].start : null;
  }

  /**
   * Check if a date is within a range (inclusive)
   */
  private isDateWithinRange(date: Date, start: string | Date, end: string | Date): boolean {
    const target = this.normalizeDate(date);
    const startDate = this.normalizeDate(start);
    const endDate = this.normalizeDate(end);
    return target >= startDate && target <= endDate;
  }

  /**
   * Normalize date to midnight local time, avoiding UTC timezone shifts
   *
   * Phase 2.1 Regression Fix: Uses local year/month/date to prevent
   * ISO string parsing issues that cause unexpected timezone conversions
   */
  private normalizeDate(value: Date | string): Date {
    const d = new Date(value);
    return new Date(d.getFullYear(), d.getMonth(), d.getDate());
  }

  /**
   * Add days to a date
   */
  private addDays(date: Date, days: number): Date {
    const result = new Date(date);
    result.setDate(result.getDate() + days);
    return result;
  }
}
