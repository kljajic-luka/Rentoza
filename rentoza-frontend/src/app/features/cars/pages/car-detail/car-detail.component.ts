import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { combineLatest, finalize, filter, map, shareReplay, switchMap, tap } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';

import { Car } from '@core/models/car.model';
import { Review } from '@core/models/review.model';
import { CarService } from '@core/services/car.service';
import { ReviewService } from '@core/services/review.service';
import { BookingService } from '@core/services/booking.service';
import { AuthService } from '@core/auth/auth.service';
import { DisplayNamePipe } from '@shared/pipes/display-name.pipe';

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
    FlexLayoutModule,
    DisplayNamePipe
  ],
  templateUrl: './car-detail.component.html',
  styleUrls: ['./car-detail.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CarDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly carService = inject(CarService);
  private readonly reviewService = inject(ReviewService);
  private readonly bookingService = inject(BookingService);
  private readonly toastr = inject(ToastrService);
  private readonly authService = inject(AuthService);

  private selectedCarId = '';

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

  readonly vm$ = combineLatest([this.car$, this.reviews$]).pipe(
    map(([car, reviews]) => ({ car, reviews }))
  );

  protected readonly isSubmitting = signal(false);

  readonly bookingForm = this.fb.nonNullable.group({
    startDate: ['', Validators.required],
    endDate: ['', Validators.required]
  });

  submitBooking(): void {
    if (this.bookingForm.invalid || !this.selectedCarId) {
      this.bookingForm.markAllAsTouched();
      return;
    }

    if (!this.authService.isAuthenticated()) {
      this.toastr.info('Please log in to request a booking.');
      return;
    }

    this.isSubmitting.set(true);

    const payload = {
      carId: this.selectedCarId,
      startDate: this.bookingForm.value.startDate!,
      endDate: this.bookingForm.value.endDate!
    };

    this.bookingService
      .createBooking(payload)
      .pipe(
        tap(() => this.bookingForm.reset()),
        finalize(() => this.isSubmitting.set(false))
      )
      .subscribe({
        next: () => {
          this.toastr.success('Booking request submitted successfully!');
        }
      });
  }

  trackReviewById(_index: number, review: Review): string {
    return review.id;
  }
}
