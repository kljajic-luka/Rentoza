import {
  Component,
  Inject,
  OnInit,
  inject,
  signal,
  DestroyRef,
  ChangeDetectorRef,
  ViewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
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
import { MatSelectModule } from '@angular/material/select';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { Car, UnavailableRange } from '@core/models/car.model';
import { BookingService } from '@core/services/booking.service';
import { CarService } from '@core/services/car.service';
import { AuthService } from '@core/auth/auth.service';
import { take, finalize } from 'rxjs';
import {
  Booking,
  generateTimeSlots,
  TimeSlot,
  combineDateTime,
  calculatePeriods,
} from '@core/models/booking.model';
import { BlockedDate } from '@core/models/blocked-date.model';
import {
  PickupLocationSelectorComponent,
  PickupLocationData,
} from '@features/bookings/components/pickup-location-selector';
import { DeliveryFeeResult } from '@core/services/location.service';
import {
  validateLeadTime,
  validateMinimumDuration,
  validateMaximumDuration,
  DEFAULT_ADVANCE_NOTICE_HOURS,
  DEFAULT_MIN_TRIP_HOURS,
  DEFAULT_MAX_TRIP_DAYS,
} from '@core/utils/time-validation.util';

export interface BookingDialogData {
  car: Car;
  bookings: Booking[];
  blockedDates: BlockedDate[];
}

/**
 * BookingDialogComponent - Exact Timestamp Architecture
 *
 * Uses precise start/end timestamps instead of date + time window.
 * - Time slots are in 30-minute intervals
 * - Minimum rental duration: 24 hours
 * - All times are Europe/Belgrade local time
 */
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
    MatSelectModule,
    PickupLocationSelectorComponent,
  ],
  templateUrl: './booking-dialog.component.html',
  styleUrls: ['./booking-dialog.component.scss'],
})
export class BookingDialogComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly router = inject(Router);
  private readonly bookingService = inject(BookingService);
  private readonly carService = inject(CarService);
  private readonly authService = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialogRef = inject(MatDialogRef<BookingDialogComponent>);
  private readonly destroyRef = inject(DestroyRef);
  private readonly cdr = inject(ChangeDetectorRef);

  // ViewChild for pickup location selector
  @ViewChild(PickupLocationSelectorComponent) pickupSelector?: PickupLocationSelectorComponent;

  // Booking constraints
  private readonly BUFFER_DAYS = 1;
  private readonly DEFAULT_MIN_RENTAL_DAYS = 1;
  private readonly DEFAULT_MAX_RENTAL_DAYS = 30;
  private readonly MAX_ADVANCE_BOOKING_DAYS = 365;
  private readonly MIN_HOURS_BEFORE_BOOKING = 1; // Minimum 1 hour before trip start

  // Time slots for 30-minute intervals
  protected readonly timeSlots: TimeSlot[] = generateTimeSlots();

  // Default times
  protected readonly defaultStartTime = '09:00';
  protected readonly defaultEndTime = '10:00';

  // Computed min/max from car settings or defaults
  protected get minRentalDays(): number {
    return this.data.car.minRentalDays ?? this.DEFAULT_MIN_RENTAL_DAYS;
  }

  protected get maxRentalDays(): number {
    return this.data.car.maxRentalDays ?? this.DEFAULT_MAX_RENTAL_DAYS;
  }

  // Duration validation signals for error display
  protected readonly durationTooShort = signal(false);
  protected readonly durationTooLong = signal(false);

  protected readonly isSubmitting = signal(false);
  protected readonly totalPrice = signal(0);
  protected readonly basePrice = signal(0);
  protected readonly insuranceCost = signal(0);
  protected readonly refuelCost = signal(0);
  protected readonly rentalDays = signal(0);
  protected readonly rentalHours = signal(0);

  // Pickup location state (Phase 2.4)
  protected readonly pickupLocation = signal<PickupLocationData | null>(null);
  protected readonly deliveryFee = signal<DeliveryFeeResult | null>(null);
  protected readonly pickupLocationValid = signal(true);

  protected readonly startDateMin = new Date();
  protected endDateMin = new Date();

  // Unavailable ranges from backend (includes bookings, blocked dates, and gaps)
  private readonly unavailableRanges = signal<UnavailableRange[]>([]);
  private readonly isLoadingAvailability = signal(false);

  // Delivery fee cost for total price (Phase 2.4)
  protected readonly deliveryFeeCost = signal(0);

  // Computed car location GeoPoint (Phase 2.4)
  protected get carGeoPoint() {
    const car = this.data.car;
    if (car.locationLatitude && car.locationLongitude) {
      return {
        latitude: car.locationLatitude,
        longitude: car.locationLongitude,
        address: car.locationAddress,
        city: car.locationCity,
      };
    }
    return null;
  }

  // Whether car offers delivery option (Phase 2.4)
  protected get deliveryAvailable(): boolean {
    return this.data.car.deliveryAvailable ?? false;
  }

  // Maximum delivery radius (Phase 2.4)
  protected get deliveryMaxRadius(): number {
    return this.data.car.deliveryMaxRadiusKm ?? 25;
  }

  protected readonly bookingForm = this.fb.nonNullable.group({
    startDate: [null as Date | null, Validators.required],
    startTime: [this.defaultStartTime, Validators.required],
    endDate: [null as Date | null, Validators.required],
    endTime: [this.defaultEndTime, Validators.required],
    driverName: [''],
    driverSurname: [''],
    driverAge: [null as number | null],
    driverPhone: [''],
    insuranceType: ['BASIC', Validators.required],
    prepaidRefuel: [false],
  });

  constructor(@Inject(MAT_DIALOG_DATA) public data: BookingDialogData) {}

  ngOnInit(): void {
    // Prefill driver details from current user
    this.authService.currentUser$.pipe(take(1)).subscribe((user) => {
      if (user) {
        this.bookingForm.patchValue({
          driverName: user.firstName || '',
          driverSurname: user.lastName || '',
          driverAge: user.age || null,
          driverPhone: user.phone || '',
        });

        // Disable driver fields for read-only
        this.bookingForm.get('driverName')?.disable({ emitEvent: false });
        this.bookingForm.get('driverSurname')?.disable({ emitEvent: false });
        this.bookingForm.get('driverAge')?.disable({ emitEvent: false });
        this.bookingForm.get('driverPhone')?.disable({ emitEvent: false });

        this.cdr.detectChanges();
      }
    });

    // Recalculate price whenever form values change
    this.bookingForm.valueChanges.subscribe(() => {
      this.calculatePrice();
    });

    // Reactive refresh for end date when start date changes
    this.bookingForm
      .get('startDate')
      ?.valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.bookingForm.get('endDate')?.updateValueAndValidity();
      });

    // Load unavailable ranges from backend
    this.loadAvailabilityRanges();
  }

  /**
   * Load unavailable time ranges for the car from the backend.
   * This includes bookings, blocked dates, and unusable gaps.
   */
  private loadAvailabilityRanges(): void {
    this.isLoadingAvailability.set(true);
    const now = new Date();
    const endDate = new Date(now);
    endDate.setFullYear(endDate.getFullYear() + 1); // 1 year ahead

    this.carService
      .getCarAvailability(Number(this.data.car.id), now.toISOString(), endDate.toISOString())
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.isLoadingAvailability.set(false))
      )
      .subscribe({
        next: (ranges) => {
          this.unavailableRanges.set(ranges);
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.error('Failed to load availability ranges:', err);
          // Continue with existing data.bookings and data.blockedDates as fallback
        },
      });
  }

  /**
   * Filter for start date - excludes unavailable dates and past dates.
   * Uses unavailable ranges from backend for accurate filtering.
   *
   * PERFORMANCE NOTE: This is O(N) per calendar cell render.
   * For MVP with < 100 bookings/year, acceptable. For optimization:
   * - Convert ranges to Set<string> of disabled dates (YYYY-MM-DD) for O(1) lookup
   * - Or use Interval Tree for O(log N) range queries
   * - Monitor calendar render performance on older mobile devices
   */
  protected readonly startDateFilter = (date: Date | null): boolean => {
    if (!date) {
      return false;
    }

    const normalized = this.normalizeDate(date);

    // Past dates
    const today = this.normalizeDate(new Date());
    if (normalized < today) {
      return false;
    }

    // Check if date is fully inside any unavailable range
    const dateStart = new Date(normalized);
    dateStart.setHours(0, 0, 0, 0);
    const dateEnd = new Date(normalized);
    dateEnd.setHours(23, 59, 59, 999);

    return !this.isDateInUnavailableRange(dateStart, dateEnd);
  };

  /**
   * Filter for end date - enforces rental constraints and prevents "jump-over".
   * Prevents selecting an end date that would span across an unavailable range.
   */
  protected readonly endDateFilter = (date: Date | null): boolean => {
    if (!date) {
      return false;
    }

    const start = this.bookingForm.controls.startDate.value;
    if (!start) {
      return this.startDateFilter(date);
    }

    const normalizedEnd = this.normalizeDate(date);
    const normalizedStart = this.normalizeDate(start);

    // Minimum rental (24 hours = at least 1 day)
    if (normalizedEnd < normalizedStart) {
      return false;
    }

    // Maximum rental duration
    if (normalizedEnd > this.addDays(normalizedStart, this.maxRentalDays)) {
      return false;
    }

    // Advance booking limit (12 months)
    const maxAdvance = this.addDays(this.normalizeDate(new Date()), this.MAX_ADVANCE_BOOKING_DAYS);
    if (normalizedEnd > maxAdvance) {
      return false;
    }

    // Check if entire range [start, end] overlaps with unavailable range
    const startDateTime = combineDateTime(
      start,
      this.bookingForm.value.startTime || this.defaultStartTime
    );
    const endDateTime = combineDateTime(
      date,
      this.bookingForm.value.endTime || this.defaultEndTime
    );

    return !this.isRangeUnavailable(startDateTime, endDateTime);
  };

  protected handleStartDateChange(date: Date | null): void {
    if (!date) return;

    // Update minimum end date to be at least the same day
    this.endDateMin = new Date(date);

    // Clear end date if it's now invalid
    const endDate = this.bookingForm.value.endDate;
    if (endDate && endDate < date) {
      this.bookingForm.patchValue({ endDate: null });
    }

    this.calculatePrice();
  }

  protected handleEndDateChange(date: Date | null): void {
    if (!date) return;
    this.calculatePrice();
  }

  private calculatePrice(): void {
    const startDate = this.bookingForm.value.startDate;
    const startTime = this.bookingForm.value.startTime;
    const endDate = this.bookingForm.value.endDate;
    const endTime = this.bookingForm.value.endTime;
    const insuranceType = this.bookingForm.value.insuranceType || 'BASIC';
    const prepaidRefuel = this.bookingForm.value.prepaidRefuel || false;

    if (!startDate || !endDate || !startTime || !endTime) {
      this.resetPriceCalculation();
      this.durationTooShort.set(false);
      this.durationTooLong.set(false);
      return;
    }

    // Combine date and time
    const startDateTime = combineDateTime(startDate, startTime);
    const endDateTime = combineDateTime(endDate, endTime);

    // Calculate hours
    const startMs = new Date(startDateTime).getTime();
    const endMs = new Date(endDateTime).getTime();
    const hours = Math.round((endMs - startMs) / (1000 * 60 * 60));
    this.rentalHours.set(hours);

    // Calculate 24-hour periods for pricing
    const periods = calculatePeriods(startDateTime, endDateTime);
    this.rentalDays.set(periods);

    // Validate duration
    this.durationTooShort.set(hours < 24); // Minimum 24 hours
    this.durationTooLong.set(periods > this.maxRentalDays);

    // Base price (per 24-hour period)
    const base = periods * this.data.car.pricePerDay;
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

    // Total (includes delivery fee if applicable)
    const deliveryCost = this.deliveryFeeCost();
    const total = base * insuranceMultiplier + refuel + deliveryCost;
    this.totalPrice.set(total);
  }

  /**
   * Handle pickup location change from selector (Phase 2.4)
   */
  protected onPickupLocationChanged(location: PickupLocationData): void {
    this.pickupLocation.set(location);
  }

  /**
   * Handle delivery fee calculation result (Phase 2.4)
   */
  protected onDeliveryFeeCalculated(fee: DeliveryFeeResult): void {
    this.deliveryFee.set(fee);
    if (fee.available && fee.fee) {
      this.deliveryFeeCost.set(fee.fee);
    } else {
      this.deliveryFeeCost.set(0);
    }
    // Recalculate total price with delivery fee
    this.calculatePrice();
  }

  /**
   * Handle pickup location validity change (Phase 2.4)
   */
  protected onPickupValidityChanged(valid: boolean): void {
    this.pickupLocationValid.set(valid);
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
    this.rentalHours.set(0);
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

    const formValues = this.bookingForm.getRawValue();
    const { driverName, driverSurname, driverAge, driverPhone } = formValues;

    if (!driverName || !driverSurname || !driverAge || !driverPhone) {
      this.snackBar.open(
        'Podaci vozača nisu kompletni. Molimo ažurirajte svoj profil.',
        'Zatvori',
        { duration: 5000, panelClass: ['snackbar-error'] }
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

    // Validate minimum hours before booking using centralized validation
    const startDateTime = combineDateTime(formValues.startDate!, formValues.startTime);
    const endDateTime = combineDateTime(formValues.endDate!, formValues.endTime);
    const startDate = new Date(startDateTime);
    const endDate = new Date(endDateTime);

    // Use car-specific or default advance notice hours
    const advanceNoticeHours = this.data.car.advanceNoticeHours ?? DEFAULT_ADVANCE_NOTICE_HOURS;
    const leadTimeValidation = validateLeadTime(startDate, advanceNoticeHours);
    if (!leadTimeValidation.valid) {
      this.snackBar.open(leadTimeValidation.errorMessage!, 'Zatvori', {
        duration: 5000,
        panelClass: ['snackbar-error'],
      });
      return;
    }

    // Validate minimum duration using centralized validation
    const minTripHours = this.data.car.minTripHours ?? DEFAULT_MIN_TRIP_HOURS;
    const minDurationValidation = validateMinimumDuration(startDate, endDate, minTripHours);
    if (!minDurationValidation.valid) {
      this.snackBar.open(minDurationValidation.errorMessage!, 'Zatvori', {
        duration: 5000,
        panelClass: ['snackbar-error'],
      });
      return;
    }

    // Validate maximum duration using centralized validation
    const maxTripDays = this.data.car.maxTripDays ?? DEFAULT_MAX_TRIP_DAYS;
    const maxDurationValidation = validateMaximumDuration(startDate, endDate, maxTripDays);
    if (!maxDurationValidation.valid) {
      this.snackBar.open(maxDurationValidation.errorMessage!, 'Zatvori', {
        duration: 5000,
        panelClass: ['snackbar-error'],
      });
      return;
    }

    // Validate that selected range doesn't overlap with unavailable ranges
    if (this.isRangeUnavailable(startDateTime, endDateTime)) {
      const reason = this.getUnavailabilityReasonForRange(startDateTime, endDateTime);
      this.snackBar.open(reason, 'Zatvori', {
        duration: 5000,
        panelClass: ['snackbar-error'],
      });
      return;
    }

    // Validate pickup location if delivery was selected (Phase 2.4)
    if (!this.pickupLocationValid()) {
      this.snackBar.open(
        'Molimo izaberite validnu lokaciju preuzimanja unutar dozvoljenog radijusa.',
        'Zatvori',
        { duration: 5000, panelClass: ['snackbar-error'] }
      );
      return;
    }

    this.isSubmitting.set(true);

    // Build payload with exact timestamps (reuse endDateTime from above)
    // Include pickup location data if available (Phase 2.4)
    const pickup = this.pickupLocation();
    const payload = {
      carId: this.data.car.id.toString(),
      startTime: startDateTime,
      endTime: endDateTime,
      driverName: formValues.driverName,
      driverSurname: formValues.driverSurname,
      driverAge: formValues.driverAge ?? undefined,
      driverPhone: formValues.driverPhone,
      insuranceType: formValues.insuranceType || 'BASIC',
      prepaidRefuel: formValues.prepaidRefuel || false,
      // Pickup location fields (Phase 2.4) - matching backend BookingRequestDTO
      pickupLatitude: pickup?.latitude,
      pickupLongitude: pickup?.longitude,
      pickupAddress: pickup?.address,
      pickupCity: pickup?.city,
      // deliveryRequested is true when user selects custom location (not car's home)
      deliveryRequested: pickup ? !pickup.isCarLocation : false,
    };

    // Validate availability before creating booking
    this.bookingService.validateBooking(payload).subscribe({
      next: (result) => {
        if (result.available) {
          this.createBookingConfirmed(payload);
        } else {
          this.snackBar.open(
            'Ovaj termin je upravo rezervisan. Molimo izaberite drugi termin.',
            'Zatvori',
            { duration: 5000, panelClass: ['snackbar-error'] }
          );
          this.isSubmitting.set(false);
        }
      },
      error: (error) => {
        if (error.status === 409) {
          this.snackBar.open(
            'Ovaj termin je upravo rezervisan. Molimo izaberite drugi termin.',
            'Zatvori',
            { duration: 5000, panelClass: ['snackbar-error'] }
          );
        } else {
          const errorMessage = error.error?.message || 'Greška pri proveri dostupnosti.';
          this.snackBar.open(errorMessage, 'Zatvori', {
            duration: 5000,
            panelClass: ['snackbar-error'],
          });
        }
        this.isSubmitting.set(false);
      },
    });
  }

  private createBookingConfirmed(payload: any): void {
    this.bookingService.createBooking(payload).subscribe({
      next: (booking) => {
        if (booking.status === 'PENDING_APPROVAL') {
          this.snackBar.open(
            'Vaš zahtev za rezervaciju je poslat! Čekamo odobrenje domaćina.',
            'Zatvori',
            { duration: 5000, panelClass: ['snackbar-info'] }
          );
        } else {
          this.snackBar.open('Vaša rezervacija je uspešno potvrđena!', 'Zatvori', {
            duration: 4000,
            panelClass: ['snackbar-success'],
          });
        }
        this.dialogRef.close(true);
      },
      error: (error) => {
        console.error('Booking error:', error);
        const errorCode = error.error?.code;
        let errorMessage: string;

        // Handle verification-related errors (403 Forbidden)
        if (error.status === 403) {
          switch (errorCode) {
            case 'VERIFICATION_REQUIRED':
            case 'LICENSE_NOT_VERIFIED':
              // User needs to verify license - redirect to verification page
              this.handleVerificationRedirect(
                'Potrebna je verifikacija vozačke dozvole pre rezervacije.'
              );
              return;
            case 'LICENSE_EXPIRED':
              // License has expired - redirect to verification for renewal
              this.handleVerificationRedirect(
                'Vaša vozačka dozvola je istekla. Molimo obnovite verifikaciju.'
              );
              return;
            case 'LICENSE_EXPIRES_BEFORE_TRIP':
              // License expires before trip ends
              this.handleVerificationRedirect(
                'Vaša vozačka dozvola ističe pre završetka putovanja. Molimo obnovite dozvolu.'
              );
              return;
            case 'VERIFICATION_PENDING':
              // Verification is in progress
              this.snackBar
                .open(
                  'Vaša verifikacija je u toku. Molimo sačekajte odobrenje.',
                  'Pogledaj status',
                  { duration: 8000, panelClass: ['snackbar-warning'] }
                )
                .onAction()
                .subscribe(() => {
                  this.dialogRef.close(false);
                  this.router.navigate(['/verify-license']);
                });
              this.isSubmitting.set(false);
              return;
            case 'VERIFICATION_REJECTED':
              // Previous verification was rejected
              this.handleVerificationRedirect(
                'Vaša prethodna verifikacija je odbijena. Molimo pošaljite ponovo.'
              );
              return;
            case 'ACCOUNT_SUSPENDED':
              // Account is suspended
              this.snackBar.open(
                'Vaš nalog je suspendovan. Kontaktirajte podršku za pomoć.',
                'Zatvori',
                { duration: 8000, panelClass: ['snackbar-error'] }
              );
              this.isSubmitting.set(false);
              return;
            default:
              // Generic 403 error
              errorMessage = error.error?.message || 'Nemate dozvolu za ovu akciju.';
          }
        } else if (error.status === 409) {
          switch (errorCode) {
            case 'USER_OVERLAP':
              errorMessage =
                'Ne možete rezervisati dva vozila u isto vreme. ' +
                'Već imate aktivnu ili čekajuću rezervaciju za ovaj period.';
              break;
            case 'CAR_UNAVAILABLE':
              errorMessage =
                'Ovaj automobil je upravo rezervisan za izabrani termin. ' +
                'Molimo izaberite drugi termin.';
              break;
            default:
              errorMessage = error.error?.message || 'Rezervacija nije moguća zbog konflikta.';
          }
        } else {
          errorMessage = error.error?.message || 'Greška pri kreiranju rezervacije.';
        }

        this.snackBar.open(errorMessage, 'Zatvori', {
          duration: 6000,
          panelClass: ['snackbar-error'],
        });
        this.isSubmitting.set(false);
      },
    });
  }

  /**
   * Handles verification-related errors by showing snackbar and redirecting to verification page.
   * Includes returnUrl to bring user back to this car after verification.
   */
  private handleVerificationRedirect(message: string): void {
    const carId = this.data.car?.id;
    const returnUrl = carId ? `/cars/${carId}` : '/cars';

    this.snackBar
      .open(message, 'Verifikuj', {
        duration: 8000,
        panelClass: ['snackbar-warning'],
      })
      .onAction()
      .subscribe(() => {
        this.dialogRef.close(false);
        this.router.navigate(['/verify-license'], { queryParams: { returnUrl } });
      });

    this.isSubmitting.set(false);
  }

  protected cancel(): void {
    this.dialogRef.close(false);
  }

  // ========== Date Filtering Helper Methods ==========

  /**
   * Check if a date range falls within any unavailable range.
   * Uses backend unavailable ranges (includes gaps).
   */
  private isDateInUnavailableRange(dateStart: Date, dateEnd: Date): boolean {
    return this.unavailableRanges().some((range) => {
      const rangeStart = new Date(range.start);
      const rangeEnd = new Date(range.end);
      // Check if date range overlaps with unavailable range
      // Overlap formula: (A.start < B.end) && (A.end > B.start)
      return dateStart < rangeEnd && dateEnd > rangeStart;
    });
  }

  /**
   * Check if a time range (startDateTime to endDateTime) overlaps with any unavailable range.
   * Used for preventing "jump-over" selections.
   */
  private isRangeUnavailable(start: string, end: string): boolean {
    const startDate = new Date(start);
    const endDate = new Date(end);

    return this.unavailableRanges().some((range) => {
      const rangeStart = new Date(range.start);
      const rangeEnd = new Date(range.end);
      // Overlap formula: (A.start < B.end) && (A.end > B.start)
      return startDate < rangeEnd && endDate > rangeStart;
    });
  }

  /**
   * Check if a specific datetime falls within any unavailable range.
   * Used for time slot filtering.
   */
  private isDateTimeInUnavailableRange(dateTime: string): boolean {
    const dt = new Date(dateTime);

    return this.unavailableRanges().some((range) => {
      const rangeStart = new Date(range.start);
      const rangeEnd = new Date(range.end);
      return dt >= rangeStart && dt <= rangeEnd;
    });
  }

  /**
   * Check if a time slot should be disabled for a given date.
   * Implements hour-level granularity filtering.
   */
  protected isTimeSlotDisabled(date: Date, timeSlot: TimeSlot): boolean {
    if (!date) {
      return false;
    }

    const selectedDate = this.normalizeDate(date);
    const now = new Date();

    // If selecting today, disable past hours
    if (this.isSameDay(selectedDate, now)) {
      const slotTime = this.parseTimeSlot(timeSlot.value);
      const currentHour = now.getHours();
      const currentMinute = now.getMinutes();

      if (
        slotTime.hour < currentHour ||
        (slotTime.hour === currentHour && slotTime.minute < currentMinute)
      ) {
        return true;
      }
    }

    // Check if this specific datetime falls in unavailable range
    const dateTime = combineDateTime(date, timeSlot.value);
    return this.isDateTimeInUnavailableRange(dateTime);
  }

  /**
   * Parse time slot string (HH:mm) into hour and minute.
   */
  private parseTimeSlot(timeStr: string): { hour: number; minute: number } {
    const [hour, minute] = timeStr.split(':').map(Number);
    return { hour, minute };
  }

  /**
   * Check if two dates are on the same day.
   */
  private isSameDay(date1: Date, date2: Date): boolean {
    return (
      date1.getFullYear() === date2.getFullYear() &&
      date1.getMonth() === date2.getMonth() &&
      date1.getDate() === date2.getDate()
    );
  }

  /**
   * Legacy method - kept for backward compatibility.
   * Now uses unavailable ranges from backend instead of local data.
   */
  private isDateUnavailable(date: Date): boolean {
    const normalized = this.normalizeDate(date);
    const dateStart = new Date(normalized);
    dateStart.setHours(0, 0, 0, 0);
    const dateEnd = new Date(normalized);
    dateEnd.setHours(23, 59, 59, 999);
    return this.isDateInUnavailableRange(dateStart, dateEnd);
  }

  private getNextUnavailableDate(from: Date): Date | null {
    const ranges = [
      ...this.data.bookings.map((b) => ({
        start: this.addDays(this.normalizeDate(new Date(b.startTime)), -this.BUFFER_DAYS),
        end: this.addDays(this.normalizeDate(new Date(b.endTime)), this.BUFFER_DAYS),
      })),
      ...this.data.blockedDates.map((b) => ({
        start: this.addDays(this.normalizeDate(b.startDate), -this.BUFFER_DAYS),
        end: this.addDays(this.normalizeDate(b.endDate), this.BUFFER_DAYS),
      })),
    ]
      .filter((r) => r.end > from)
      .sort((a, b) => a.start.getTime() - b.start.getTime());

    return ranges.length > 0 ? ranges[0].start : null;
  }

  private isDateWithinRange(date: Date, start: string | Date, end: string | Date): boolean {
    const target = this.normalizeDate(date);
    const startDate = this.normalizeDate(start);
    const endDate = this.normalizeDate(end);
    return target >= startDate && target <= endDate;
  }

  private normalizeDate(value: Date | string): Date {
    const d = new Date(value);
    return new Date(d.getFullYear(), d.getMonth(), d.getDate());
  }

  private addDays(date: Date, days: number): Date {
    const result = new Date(date);
    result.setDate(result.getDate() + days);
    return result;
  }

  /**
   * Format duration for display.
   */
  protected formatDuration(): string {
    const hours = this.rentalHours();
    if (hours < 24) {
      return `${hours} sat${hours === 1 ? '' : hours < 5 ? 'a' : 'i'}`;
    }
    const days = this.rentalDays();
    return `${days} dan${days === 1 ? '' : days < 5 ? 'a' : 'a'} (${hours}h)`;
  }

  /**
   * Get unavailability reason message for a specific date.
   * Used for tooltips.
   */
  protected getUnavailabilityReason(date: Date): string {
    if (!date) {
      return '';
    }

    const dateStart = new Date(this.normalizeDate(date));
    dateStart.setHours(0, 0, 0, 0);
    const dateEnd = new Date(this.normalizeDate(date));
    dateEnd.setHours(23, 59, 59, 999);

    const range = this.findOverlappingRange(dateStart, dateEnd);
    if (!range) {
      return '';
    }

    switch (range.reason) {
      case 'BOOKING':
        return 'Vozilo je već rezervisano u ovom periodu';
      case 'BLOCKED_DATE':
        return 'Vlasnik je blokirao ove datume';
      case 'GAP_TOO_SMALL':
        return 'Period je prekratak za minimalno trajanje';
      default:
        return 'Datum nije dostupan';
    }
  }

  /**
   * Get unavailability reason message for a time range.
   * Used for error messages in submitBooking.
   */
  private getUnavailabilityReasonForRange(start: string, end: string): string {
    const startDate = new Date(start);
    const endDate = new Date(end);

    const range = this.findOverlappingRange(startDate, endDate);
    if (!range) {
      return 'Izabrani period nije dostupan.';
    }

    switch (range.reason) {
      case 'BOOKING':
        return 'Vozilo je već rezervisano u ovom periodu. Molimo izaberite drugi termin.';
      case 'BLOCKED_DATE':
        return 'Vlasnik je blokirao ove datume. Molimo izaberite drugi termin.';
      case 'GAP_TOO_SMALL':
        return 'Period je prekratak za minimalno trajanje iznajmljivanja.';
      default:
        return 'Izabrani period nije dostupan.';
    }
  }

  /**
   * Find the first unavailable range that overlaps with the given date range.
   */
  private findOverlappingRange(dateStart: Date, dateEnd: Date): UnavailableRange | null {
    return (
      this.unavailableRanges().find((range) => {
        const rangeStart = new Date(range.start);
        const rangeEnd = new Date(range.end);
        return dateStart < rangeEnd && dateEnd > rangeStart;
      }) || null
    );
  }
}
