import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  Inject,
  inject,
  signal,
  computed,
} from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import {
  MatDialogModule,
  MatDialog,
  MatDialogRef,
  MAT_DIALOG_DATA,
} from '@angular/material/dialog';
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

import { Car, Feature, CAR_RENTAL_RULES } from '@core/models/car.model';
import { Review } from '@core/models/review.model';
import { CarService } from '@core/services/car.service';
import { ReviewService } from '@core/services/review.service';
import { BookingService } from '@core/services/booking.service';
import { AvailabilityService } from '@core/services/availability.service';
import { AuthService } from '@core/auth/auth.service';
import { Booking } from '@core/models/booking.model';
import { BlockedDate } from '@core/models/blocked-date.model';
import { FavoriteButtonComponent } from '@shared/components/favorite-button/favorite-button.component';
import { TranslateEnumPipe, FeatureHelper } from '@shared/pipes/translate-enum.pipe';

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
    MatChipsModule,
    MatDividerModule,
    MatDialogModule,
    FlexLayoutModule,
    RouterModule,
    FavoriteButtonComponent,
    TranslateEnumPipe,
  ],
  templateUrl: './car-detail.component.html',
  styleUrls: ['./car-detail.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CarDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);
  private readonly carService = inject(CarService);
  private readonly reviewService = inject(ReviewService);
  private readonly bookingService = inject(BookingService);
  private readonly availabilityService = inject(AvailabilityService);
  private readonly toastr = inject(ToastrService);
  private readonly authService = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly dialog = inject(MatDialog);

  private selectedCarId = '';
  readonly bookingsSubject = new BehaviorSubject<Booking[]>([]);
  readonly blockedDatesSubject = new BehaviorSubject<BlockedDate[]>([]);
  startDateMin: Date = this.normalizeDate(new Date());
  endDateMin: Date = this.addDays(this.startDateMin, 1);

  // Image carousel state
  protected readonly currentImageIndex = signal(0);
  protected readonly isImageLoading = signal(false);

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
  readonly isOwner$ = this.authService.currentUser$.pipe(
    map((user) => user?.roles?.includes('OWNER') ?? false)
  );

  // Car rental rules and feature helper
  protected readonly rentalRules = CAR_RENTAL_RULES;
  protected readonly featureHelper = FeatureHelper;

  // Categorized features for display
  protected categorizeFeatures(features: Feature[] | undefined) {
    if (!features || features.length === 0) {
      return null;
    }
    return FeatureHelper.categorize(features);
  }

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
    // Load both bookings and blocked dates for availability checking
    this.carId$
      .pipe(
        switchMap((id) =>
          combineLatest({
            bookings: this.bookingService.getBookingsForCar(id).pipe(
              catchError(() => {
                // Guest users can't access bookings - that's fine, just use empty array
                return of([]);
              })
            ),
            blockedDates: this.availabilityService.getBlockedDatesForCar(+id).pipe(
              catchError(() => {
                // If blocked dates fail to load, use empty array
                return of([]);
              })
            ),
          })
        ),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe(({ bookings, blockedDates }) => {
        this.updateBookings(bookings);
        this.blockedDatesSubject.next(blockedDates);
      });
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
        next: (booking) => {
          this.toastr.success(
            'Booking request submitted successfully! A new conversation has been created. Check your Messages.',
            'Success',
            { timeOut: 5000 }
          );
          this.refreshBookings();

          // No automatic redirect - user can navigate to Messages manually
          // The backend will create a conversation automatically via ChatServiceClient
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

    // Check if date is in any booking
    const isBooked = this.bookingsSubject.value.some((booking) =>
      this.isDateWithinRange(normalized, booking.startDate, booking.endDate)
    );

    // Check if date is in any blocked range
    const isBlocked = this.blockedDatesSubject.value.some((blocked) =>
      this.isDateWithinRange(normalized, blocked.startDate, blocked.endDate)
    );

    return isBooked || isBlocked;
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

    // Combine bookings and blocked dates for checking
    const unavailableRanges = [
      ...this.bookingsSubject.value.map((b) => ({
        startDate: b.startDate,
        endDate: b.endDate,
      })),
      ...this.blockedDatesSubject.value.map((b) => ({
        startDate: b.startDate,
        endDate: b.endDate,
      })),
    ].sort(
      (a, b) =>
        this.normalizeDate(a.startDate).getTime() - this.normalizeDate(b.startDate).getTime()
    );

    let adjusted = true;
    while (adjusted) {
      adjusted = false;
      for (const range of unavailableRanges) {
        const startDate = this.normalizeDate(range.startDate);
        const endDate = this.normalizeDate(range.endDate);

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
    // Check if range overlaps with any booking
    const hasBookingConflict = this.bookingsSubject.value.some((booking) =>
      this.rangesOverlap(
        start,
        end,
        this.normalizeDate(booking.startDate),
        this.normalizeDate(booking.endDate)
      )
    );

    // Check if range overlaps with any blocked date range
    const hasBlockedConflict = this.blockedDatesSubject.value.some((blocked) =>
      this.rangesOverlap(
        start,
        end,
        this.normalizeDate(blocked.startDate),
        this.normalizeDate(blocked.endDate)
      )
    );

    return !hasBookingConflict && !hasBlockedConflict;
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

  // ========== Image Carousel Methods ==========

  /**
   * Get all images for carousel (imageUrls or fallback to single imageUrl)
   */
  protected getCarImages(car: Car): string[] {
    if (car.imageUrls && car.imageUrls.length > 0) {
      return car.imageUrls;
    }
    return car.imageUrl ? [car.imageUrl] : [];
  }

  /**
   * Get current displayed image
   */
  protected getCurrentImage(car: Car): string | undefined {
    const images = this.getCarImages(car);
    return images[this.currentImageIndex()] || images[0];
  }

  /**
   * Navigate to previous image (with looping)
   */
  protected previousImage(car: Car, event?: Event): void {
    event?.stopPropagation();
    const images = this.getCarImages(car);
    if (images.length <= 1) return;

    const newIndex =
      this.currentImageIndex() === 0 ? images.length - 1 : this.currentImageIndex() - 1;
    this.currentImageIndex.set(newIndex);
  }

  /**
   * Navigate to next image (with looping)
   */
  protected nextImage(car: Car, event?: Event): void {
    event?.stopPropagation();
    const images = this.getCarImages(car);
    if (images.length <= 1) return;

    const newIndex =
      this.currentImageIndex() === images.length - 1 ? 0 : this.currentImageIndex() + 1;
    this.currentImageIndex.set(newIndex);
  }

  /**
   * Open fullscreen image viewer
   */
  protected openFullscreenViewer(car: Car): void {
    const images = this.getCarImages(car);
    if (images.length === 0) return;

    this.dialog.open(ImageViewerDialogComponent, {
      data: {
        images,
        currentIndex: this.currentImageIndex(),
        carName: `${car.make} ${car.model}`,
      },
      panelClass: 'fullscreen-dialog',
      maxWidth: '100vw',
      maxHeight: '100vh',
      width: '100%',
      height: '100%',
    });
  }

  /**
   * Handle keyboard navigation (left/right arrows)
   */
  @HostListener('window:keydown', ['$event'])
  protected handleKeyboardNavigation(event: KeyboardEvent, car?: Car): void {
    if (!car) return;

    if (event.key === 'ArrowLeft') {
      this.previousImage(car);
    } else if (event.key === 'ArrowRight') {
      this.nextImage(car);
    }
  }
}

// ========== Fullscreen Image Viewer Dialog Component ==========

@Component({
  selector: 'app-image-viewer-dialog',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatButtonModule, MatDialogModule],
  template: `
    <div class="image-viewer">
      <button
        mat-icon-button
        class="close-button"
        (click)="close()"
        aria-label="Close fullscreen viewer"
      >
        <mat-icon>close</mat-icon>
      </button>

      <div class="image-container" (swipeleft)="next()" (swiperight)="previous()">
        @if (isLoading()) {
        <div class="loading-indicator">
          <mat-icon>hourglass_empty</mat-icon>
          <p>Učitavanje slike...</p>
        </div>
        }

        <img
          [src]="currentImage()"
          [alt]="data.carName"
          (load)="onImageLoad()"
          (error)="onImageError()"
          [class.loaded]="!isLoading()"
        />

        @if (data.images.length > 1) {
        <button
          mat-icon-button
          class="nav-button nav-button--left"
          (click)="previous()"
          [disabled]="isLoading()"
          aria-label="Previous image"
        >
          <mat-icon>chevron_left</mat-icon>
        </button>

        <button
          mat-icon-button
          class="nav-button nav-button--right"
          (click)="next()"
          [disabled]="isLoading()"
          aria-label="Next image"
        >
          <mat-icon>chevron_right</mat-icon>
        </button>

        <div class="image-counter">{{ currentIndex() + 1 }} / {{ data.images.length }}</div>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .image-viewer {
        position: relative;
        width: 100vw;
        height: 100vh;
        background: rgba(0, 0, 0, 0.95);
        display: flex;
        align-items: center;
        justify-content: center;
      }

      .close-button {
        position: absolute;
        top: 1rem;
        right: 1rem;
        z-index: 10;
        background: rgba(255, 255, 255, 0.1);
        color: white;
        backdrop-filter: blur(10px);
        width: 48px;
        height: 48px;

        mat-icon {
          font-size: 28px;
          width: 28px;
          height: 28px;
        }

        &:hover {
          background: rgba(255, 255, 255, 0.2);
        }
      }

      .image-container {
        position: relative;
        width: 100%;
        height: 100%;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 4rem 2rem 2rem;

        img {
          max-width: 100%;
          max-height: 100%;
          object-fit: contain;
          opacity: 0;
          transition: opacity 0.3s ease;

          &.loaded {
            opacity: 1;
          }
        }
      }

      .loading-indicator {
        position: absolute;
        top: 50%;
        left: 50%;
        transform: translate(-50%, -50%);
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 1rem;
        color: white;

        mat-icon {
          font-size: 48px;
          width: 48px;
          height: 48px;
          animation: pulse 1.5s ease-in-out infinite;
        }

        @keyframes pulse {
          0%,
          100% {
            opacity: 0.4;
          }
          50% {
            opacity: 1;
          }
        }
      }

      .nav-button {
        position: absolute;
        top: 50%;
        transform: translateY(-50%);
        background: rgba(255, 255, 255, 0.1);
        color: white;
        backdrop-filter: blur(10px);
        width: 56px;
        height: 56px;
        transition: all 0.2s ease;

        mat-icon {
          font-size: 32px;
          width: 32px;
          height: 32px;
        }

        &:hover:not(:disabled) {
          background: rgba(255, 255, 255, 0.2);
          transform: translateY(-50%) scale(1.1);
        }

        &:disabled {
          opacity: 0.3;
          cursor: not-allowed;
        }

        &--left {
          left: 2rem;
        }

        &--right {
          right: 2rem;
        }

        @media (max-width: 768px) {
          width: 48px;
          height: 48px;

          mat-icon {
            font-size: 28px;
            width: 28px;
            height: 28px;
          }

          &--left {
            left: 1rem;
          }

          &--right {
            right: 1rem;
          }
        }
      }

      .image-counter {
        position: absolute;
        bottom: 2rem;
        left: 50%;
        transform: translateX(-50%);
        background: rgba(0, 0, 0, 0.6);
        color: white;
        padding: 0.5rem 1rem;
        border-radius: 20px;
        font-size: 0.9rem;
        font-weight: 500;
        backdrop-filter: blur(10px);
      }
    `,
  ],
})
export class ImageViewerDialogComponent {
  protected readonly currentIndex = signal(0);
  protected readonly isLoading = signal(true);

  constructor(
    @Inject(MAT_DIALOG_DATA)
    protected readonly data: {
      images: string[];
      currentIndex: number;
      carName: string;
    },
    private readonly dialogRef: MatDialogRef<ImageViewerDialogComponent>
  ) {
    this.currentIndex.set(data.currentIndex);
  }

  protected currentImage = computed(() => this.data.images[this.currentIndex()]);

  protected close(): void {
    this.dialogRef.close();
  }

  protected previous(): void {
    if (this.isLoading()) return;

    this.isLoading.set(true);
    const newIndex =
      this.currentIndex() === 0 ? this.data.images.length - 1 : this.currentIndex() - 1;
    this.currentIndex.set(newIndex);
  }

  protected next(): void {
    if (this.isLoading()) return;

    this.isLoading.set(true);
    const newIndex =
      this.currentIndex() === this.data.images.length - 1 ? 0 : this.currentIndex() + 1;
    this.currentIndex.set(newIndex);
  }

  protected onImageLoad(): void {
    this.isLoading.set(false);
  }

  protected onImageError(): void {
    this.isLoading.set(false);
  }

  @HostListener('window:keydown', ['$event'])
  protected handleKeyboard(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      this.close();
    } else if (event.key === 'ArrowLeft') {
      this.previous();
    } else if (event.key === 'ArrowRight') {
      this.next();
    }
  }
}
