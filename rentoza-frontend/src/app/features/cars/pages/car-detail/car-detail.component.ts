import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  BehaviorSubject,
  catchError,
  combineLatest,
  finalize,
  filter,
  map,
  of,
  shareReplay,
  switchMap,
  take,
  tap,
} from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';

import { Car } from '@core/models/car.model';
import { Review } from '@core/models/review.model';
import { CarService } from '@core/services/car.service';
import { ReviewService } from '@core/services/review.service';
import { BookingService } from '@core/services/booking.service';
import { AuthService } from '@core/auth/auth.service';
import { Booking } from '@core/models/booking.model';
import { FavoriteButtonComponent } from '@shared/components/favorite-button/favorite-button.component';

@Component({
  selector: 'app-car-detail',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatDatepickerModule,
    MatNativeDateModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    MatIconModule,
    FlexLayoutModule,
    RouterModule,
    FavoriteButtonComponent,
  ],
  templateUrl: './car-detail.component.html',
  styleUrls: ['./car-detail.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CarDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly carService = inject(CarService);
  private readonly reviewService = inject(ReviewService);
  private readonly bookingService = inject(BookingService);
  private readonly toastr = inject(ToastrService);
  private readonly authService = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  private selectedCarId = '';
  readonly bookingsSubject = new BehaviorSubject<Booking[]>([]);
  startDateMin: Date = this.normalizeDate(new Date());
  endDateMin: Date = this.addDays(this.startDateMin, 1);

  private readonly carId$ = this.route.paramMap.pipe(
    map((params) => params.get('id')),
    filter((id): id is string => !!id),
    tap((id) => (this.selectedCarId = id))
  );

  readonly car$ = this.carId$.pipe(
    switchMap((id) => this.carService.getCarById(id)),
    shareReplay({ refCount: true, bufferSize: 1 })
  );

  readonly reviews$ = this.carId$.pipe(switchMap((id) => this.reviewService.getReviewsForCar(id)));

  readonly bookings$ = this.bookingsSubject.asObservable();

  readonly vm$ = combineLatest([this.car$, this.reviews$, this.bookings$]).pipe(
    map(([car, reviews, bookings]) => ({ car, reviews, bookings }))
  );

  protected readonly isSubmitting = signal(false);
  readonly isAuthenticated$ = this.authService.currentUser$;

  readonly dateClass = (date: Date): string | string[] => {
    if (this.isDateUnavailable(date)) {
      return 'date-disabled';
    }
    return [];
  };

  readonly startDateFilter = (date: Date | null): boolean => {
    if (!date) {
      return false;
    }
    const normalized = this.normalizeDate(date);
    return normalized >= this.startDateMin && !this.isDateUnavailable(normalized);
  };

  readonly endDateFilter = (date: Date | null): boolean => {
    if (!date) {
      return false;
    }
    const start = this.bookingForm.controls.startDate.value;
    if (!start) {
      return false;
    }

    const normalizedEnd = this.normalizeDate(date);
    const normalizedStart = this.normalizeDate(start);

    return normalizedEnd > normalizedStart && this.isDateRangeFree(normalizedStart, normalizedEnd);
  };

  readonly bookingForm = this.fb.group({
    startDate: this.fb.control<Date | null>(null, Validators.required),
    endDate: this.fb.control<Date | null>(null, Validators.required),
  });

  constructor() {
    this.carId$
      .pipe(
        switchMap((id) => this.bookingService.getBookingsForCar(id)),
        catchError(() => {
          // Guest users can't access bookings - that's fine, just use empty array
          return of([]);
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((bookings) => this.updateBookings(bookings));
  }

  submitBooking(): void {
    if (this.bookingForm.invalid || !this.selectedCarId) {
      this.bookingForm.markAllAsTouched();
      return;
    }

    const startDate = this.bookingForm.controls.startDate.value;
    const endDate = this.bookingForm.controls.endDate.value;

    if (!startDate || !endDate) {
      this.bookingForm.markAllAsTouched();
      return;
    }

    if (!this.isDateRangeFree(this.normalizeDate(startDate), this.normalizeDate(endDate))) {
      this.toastr.warning('Odabrani datumi nisu dostupni. Molimo izaberite druge datume.');
      return;
    }

    this.isSubmitting.set(true);

    const payload = {
      carId: this.selectedCarId,
      startDate: startDate.toISOString(),
      endDate: endDate.toISOString(),
    };

    this.bookingService
      .createBooking(payload)
      .pipe(
        tap(() => {
          this.bookingForm.reset();
          this.updateEndDateMin(this.startDateMin);
        }),
        finalize(() => this.isSubmitting.set(false))
      )
      .subscribe({
        next: () => {
          this.toastr.success('Booking request submitted successfully!');
          this.refreshBookings();
        },
      });
  }

  trackReviewById(_index: number, review: Review): string {
    return review.id;
  }

  handleStartDateChange(date: Date | null): void {
    if (!date) {
      this.bookingForm.controls.endDate.setValue(null);
      this.endDateMin = this.addDays(this.startDateMin, 1);
      return;
    }

    const normalized = this.normalizeDate(date);

    if (this.isDateUnavailable(normalized)) {
      const nextAvailable = this.getNextAvailableDate(normalized);
      this.toastr.warning('Odabrani datumi nisu dostupni. Molimo izaberite druge datume.');
      this.bookingForm.controls.startDate.setValue(nextAvailable);
      this.updateEndDateMin(nextAvailable);
      this.bookingForm.controls.endDate.setValue(null);
      return;
    }

    this.updateEndDateMin(normalized);

    const currentEnd = this.bookingForm.controls.endDate.value;
    if (!currentEnd || this.normalizeDate(currentEnd) <= normalized) {
      const nextDay = this.addDays(normalized, 1);
      this.bookingForm.controls.endDate.setValue(nextDay);
    } else if (!this.isDateRangeFree(normalized, this.normalizeDate(currentEnd))) {
      this.bookingForm.controls.endDate.setValue(null);
    }
  }

  handleEndDateChange(date: Date | null): void {
    if (!date) {
      return;
    }

    const start = this.bookingForm.controls.startDate.value;
    if (!start) {
      return;
    }

    const normalizedEnd = this.normalizeDate(date);
    const normalizedStart = this.normalizeDate(start);

    if (normalizedEnd <= normalizedStart) {
      const nextDay = this.addDays(normalizedStart, 1);
      this.bookingForm.controls.endDate.setValue(nextDay);
      return;
    }

    if (!this.isDateRangeFree(normalizedStart, normalizedEnd)) {
      this.bookingForm.controls.endDate.setValue(null);
      this.toastr.warning('Odabrani datumi nisu dostupni. Molimo izaberite druge datume.');
    }
  }

  private isDateUnavailable(date: Date): boolean {
    const normalized = this.normalizeDate(date);
    return this.bookingsSubject.value.some((booking) =>
      this.isDateWithinRange(normalized, booking.startDate, booking.endDate)
    );
  }

  private rangesOverlap(startA: Date, endA: Date, startB: Date, endB: Date): boolean {
    return startA <= endB && startB <= endA;
  }

  private isDateWithinRange(date: Date, start: string, end: string): boolean {
    const target = this.normalizeDate(date);
    const startDate = this.normalizeDate(start);
    const endDate = this.normalizeDate(end);
    return target >= startDate && target <= endDate;
  }

  private getNextAvailableDate(date: Date): Date {
    let candidate = this.normalizeDate(date);
    const today = this.normalizeDate(new Date());
    if (candidate < today) {
      candidate = today;
    }

    const sorted = [...this.bookingsSubject.value].sort(
      (a, b) =>
        this.normalizeDate(a.startDate).getTime() - this.normalizeDate(b.startDate).getTime()
    );

    let adjusted = true;
    while (adjusted) {
      adjusted = false;
      for (const booking of sorted) {
        const startDate = this.normalizeDate(booking.startDate);
        const endDate = this.normalizeDate(booking.endDate);

        if (candidate >= startDate && candidate <= endDate) {
          candidate = this.addDays(endDate, 1);
          adjusted = true;
          break;
        }
      }
    }

    return candidate;
  }

  private isDateRangeFree(start: Date, end: Date): boolean {
    return !this.bookingsSubject.value.some((booking) =>
      this.rangesOverlap(
        start,
        end,
        this.normalizeDate(booking.startDate),
        this.normalizeDate(booking.endDate)
      )
    );
  }

  private updateBookings(bookings: Booking[]): void {
    this.bookingsSubject.next(bookings);
    this.startDateMin = this.getNextAvailableDate(new Date());
    this.endDateMin = this.addDays(this.startDateMin, 1);
  }

  private refreshBookings(): void {
    if (!this.selectedCarId) {
      return;
    }

    this.bookingService
      .getBookingsForCar(this.selectedCarId)
      .pipe(take(1))
      .subscribe((bookings) => this.updateBookings(bookings));
  }

  private updateEndDateMin(startDate: Date): void {
    this.endDateMin = this.addDays(startDate, 1);
  }

  private normalizeDate(value: Date | string): Date {
    const date = new Date(value);
    date.setHours(0, 0, 0, 0);
    return date;
  }

  private addDays(date: Date, days: number): Date {
    const result = new Date(date);
    result.setDate(result.getDate() + days);
    return result;
  }
}
