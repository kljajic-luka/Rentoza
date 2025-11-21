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
import { BookingDialogComponent } from '@features/cars/dialogs/booking-dialog/booking-dialog.component';
import { MatSnackBar } from '@angular/material/snack-bar';

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
  private readonly snackBar = inject(MatSnackBar);

  // ========== Booking Constants (Phase 2.1) ==========
  private readonly BUFFER_DAYS = 1;
  private readonly MAX_RENTAL_DAYS = 30;
  private readonly MAX_ADVANCE_BOOKING_DAYS = 365;

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
  readonly loginQueryParams = computed(() => ({ returnUrl: this.router.url }));

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
    // This ensures we only block dates that come AFTER the selected start date
    const nextBlocked = this.getNextUnavailableDate(this.addDays(normalizedStart, 1));

    // 5. Permit all dates before the next blocked segment (exclusive)
    if (nextBlocked && normalizedEnd >= nextBlocked) {
      return false;
    }

    return true;
  };

  readonly bookingForm = this.fb.group({
    startDate: this.fb.control<Date | null>(null, Validators.required),
    endDate: this.fb.control<Date | null>(null, Validators.required),
  });

  constructor() {
    // Load both bookings and blocked dates for availability checking
    // PUBLIC ENDPOINT FIX: Use getPublicBookingsForCar instead of getBookingsForCar
    // - getBookingsForCar: OWNER/ADMIN only (returns full booking DTOs)
    // - getPublicBookingsForCar: accessible to all users (returns minimal date ranges)
    this.carId$
      .pipe(
        switchMap((id) =>
          combineLatest({
            bookings: this.bookingService.getPublicBookingsForCar(id).pipe(
              catchError((error) => {
                // Log for debugging but don't fail - calendar can work with just blocked dates
                console.warn('Failed to load public booking slots:', error);
                return of([]);
              })
            ),
            blockedDates: this.availabilityService.getBlockedDatesForCar(+id).pipe(
              catchError((error) => {
                // Log for debugging but don't fail
                console.warn('Failed to load blocked dates:', error);
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

    // Phase 2.1 Regression Fix: Reactive refresh - force end date re-evaluation when start date changes
    this.bookingForm
      .get('startDate')
      ?.valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.bookingForm.get('endDate')?.updateValueAndValidity();
      });
  }

  openBookingDialog(car: Car): void {
    // Phase 2.1 Regression Fix: Pass data arrays only, dialog computes its own filters
    const dialogRef = this.dialog.open(BookingDialogComponent, {
      data: {
        car,
        bookings: this.bookingsSubject.value,
        blockedDates: this.blockedDatesSubject.value,
      },
      width: '700px',
      maxWidth: '90vw',
      disableClose: false,
    });

    dialogRef.afterClosed().subscribe((result) => {
      if (result === true) {
        // Booking was successful
        this.snackBar.open('Uspešno ste rezervisali vozilo!', 'Zatvori', {
          duration: 4000,
          horizontalPosition: 'center',
          verticalPosition: 'top',
        });

        // Refresh bookings to show the new blocked dates
        this.refreshBookings();
      }
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

    // Phase 2.1 Regression Fix: Strengthen submit-time validation with defensive guard
    if (!this.isDateRangeFree(normalizedStart, normalizedEnd)) {
      this.snackBar.open(
        'Odabrani datumi nisu dostupni. Molimo izaberite druge datume.',
        'Zatvori',
        {
          duration: 5000,
          panelClass: ['snackbar-error'],
        }
      );
      this.bookingForm.controls.endDate.setValue(null);
    }
  }

  /**
   * Find the next unavailable date AFTER a given date (including buffer days)
   * Returns null if no blocked dates exist after the given date
   *
   * Phase 2.1 Regression Fix: Now filters out ranges that END before 'from' date,
   * ensuring we only return truly future blocked segments.
   *
   * @param from Starting date to search from (typically start + 1 day)
   * @returns The start of the next blocked range or null
   */
  private getNextUnavailableDate(from: Date): Date | null {
    const ranges = [
      ...this.bookingsSubject.value.map((b) => ({
        start: this.addDays(this.normalizeDate(b.startDate), -this.BUFFER_DAYS),
        end: this.addDays(this.normalizeDate(b.endDate), this.BUFFER_DAYS),
      })),
      ...this.blockedDatesSubject.value.map((b) => ({
        start: this.addDays(this.normalizeDate(b.startDate), -this.BUFFER_DAYS),
        end: this.addDays(this.normalizeDate(b.endDate), this.BUFFER_DAYS),
      })),
    ]
      .filter((r) => r.end > from) // Ignore past ranges that end before 'from'
      .sort((a, b) => a.start.getTime() - b.start.getTime());

    return ranges.length > 0 ? ranges[0].start : null;
  }

  /**
   * Calculate maximum rental end date based on car's max rental days or default
   * @param startDate Booking start date
   * @returns Maximum allowed end date
   */
  private getMaxRentalEndDate(startDate: Date): Date {
    const car = this.vm$;
    // Get maxRentalDays from car or use default
    const maxDays = this.MAX_RENTAL_DAYS; // We'll enhance this to use car.maxRentalDays once we have it in context
    return this.addDays(startDate, maxDays);
  }

  private isDateUnavailable(date: Date): boolean {
    const normalized = this.normalizeDate(date);

    // Check if date is in any booking (with buffer days)
    const isBooked = this.bookingsSubject.value.some((booking) =>
      this.isDateWithinRange(
        normalized,
        this.addDays(this.normalizeDate(booking.startDate), -this.BUFFER_DAYS),
        this.addDays(this.normalizeDate(booking.endDate), this.BUFFER_DAYS)
      )
    );

    // Check if date is in any blocked range (with buffer days)
    const isBlocked = this.blockedDatesSubject.value.some((blocked) =>
      this.isDateWithinRange(
        normalized,
        this.addDays(this.normalizeDate(blocked.startDate), -this.BUFFER_DAYS),
        this.addDays(this.normalizeDate(blocked.endDate), this.BUFFER_DAYS)
      )
    );

    return isBooked || isBlocked;
  }

  private rangesOverlap(startA: Date, endA: Date, startB: Date, endB: Date): boolean {
    return startA <= endB && startB <= endA;
  }

  private isDateWithinRange(date: Date, start: string | Date, end: string | Date): boolean {
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

    // Combine bookings and blocked dates for checking (including buffer days)
    const unavailableRanges = [
      ...this.bookingsSubject.value.map((b) => ({
        startDate: this.addDays(this.normalizeDate(b.startDate), -this.BUFFER_DAYS),
        endDate: this.addDays(this.normalizeDate(b.endDate), this.BUFFER_DAYS),
      })),
      ...this.blockedDatesSubject.value.map((b) => ({
        startDate: this.addDays(this.normalizeDate(b.startDate), -this.BUFFER_DAYS),
        endDate: this.addDays(this.normalizeDate(b.endDate), this.BUFFER_DAYS),
      })),
    ].sort((a, b) => a.startDate.getTime() - b.startDate.getTime());

    let adjusted = true;
    while (adjusted) {
      adjusted = false;
      for (const range of unavailableRanges) {
        if (candidate >= range.startDate && candidate <= range.endDate) {
          candidate = this.addDays(range.endDate, 1);
          adjusted = true;
          break;
        }
      }
    }

    return candidate;
  }

  private isDateRangeFree(start: Date, end: Date): boolean {
    // Check if range overlaps with any booking (including buffer days)
    const hasBookingConflict = this.bookingsSubject.value.some((booking) =>
      this.rangesOverlap(
        start,
        end,
        this.addDays(this.normalizeDate(booking.startDate), -this.BUFFER_DAYS),
        this.addDays(this.normalizeDate(booking.endDate), this.BUFFER_DAYS)
      )
    );

    // Check if range overlaps with any blocked date range (including buffer days)
    const hasBlockedConflict = this.blockedDatesSubject.value.some((blocked) =>
      this.rangesOverlap(
        start,
        end,
        this.addDays(this.normalizeDate(blocked.startDate), -this.BUFFER_DAYS),
        this.addDays(this.normalizeDate(blocked.endDate), this.BUFFER_DAYS)
      )
    );

    return !hasBookingConflict && !hasBlockedConflict;
  }

  /**
   * Update bookings for calendar availability.
   * Accepts both Booking[] (full DTOs) and BookingSlotDto[] (minimal public DTOs).
   * Calendar only needs startDate and endDate, so both work.
   */
  private updateBookings(
    bookings: Booking[] | import('@core/models/booking.model').BookingSlotDto[]
  ): void {
    // Cast to Booking[] for type safety - calendar only uses startDate/endDate which both types have
    this.bookingsSubject.next(bookings as any);
    this.startDateMin = this.getNextAvailableDate(new Date());
    this.endDateMin = this.addDays(this.startDateMin, 1);
  }

  private refreshBookings(): void {
    if (!this.selectedCarId) {
      return;
    }

    // Use public endpoint for refresh as well
    this.bookingService
      .getPublicBookingsForCar(this.selectedCarId)
      .pipe(take(1))
      .subscribe((bookings) => this.updateBookings(bookings));
  }

  private updateEndDateMin(startDate: Date): void {
    this.endDateMin = this.addDays(startDate, 1);
  }

  /**
   * Normalize date to midnight local time, avoiding UTC timezone shifts
   *
   * Phase 2.1 Regression Fix: Uses local year/month/date to prevent
   * ISO string parsing issues that cause unexpected timezone conversions
   *
   * @param value Date object or ISO string
   * @returns Date normalized to 00:00:00 local time
   */
  private normalizeDate(value: Date | string): Date {
    const d = new Date(value);
    return new Date(d.getFullYear(), d.getMonth(), d.getDate());
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
