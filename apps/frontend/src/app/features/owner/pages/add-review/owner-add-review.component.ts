import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { catchError, of, tap } from 'rxjs';

import { ReviewService } from '@core/services/review.service';
import { BookingService } from '@core/services/booking.service';
import { Booking } from '@core/models/booking.model';
import { OwnerReviewRequest, OWNER_REVIEW_CATEGORIES } from '@core/models/review.model';

@Component({
  selector: 'app-owner-add-review',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatDividerModule,
  ],
  templateUrl: './owner-add-review.component.html',
  styleUrls: ['./owner-add-review.component.scss'],
})
export class OwnerAddReviewComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly reviewService = inject(ReviewService);
  private readonly bookingService = inject(BookingService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(FormBuilder);

  protected readonly bookingId = signal<number>(0);
  protected readonly booking = signal<Booking | null>(null);
  protected readonly isLoading = signal(true);
  protected readonly isSubmitting = signal(false);

  // Create category signals with ratings
  protected readonly categories = OWNER_REVIEW_CATEGORIES.map((cat) => ({
    ...cat,
    rating: signal(0),
  }));

  protected readonly reviewForm = this.fb.nonNullable.group({
    comment: ['', [Validators.maxLength(500)]],
  });

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.bookingId.set(+id);
      this.loadBooking();
    } else {
      this.snackBar.open('Nevažeći ID rezervacije.', 'Zatvori', {
        duration: 3000,
        panelClass: ['snackbar-error'],
      });
      this.router.navigate(['/owner/bookings']);
    }
  }

  private loadBooking(): void {
    this.isLoading.set(true);

    this.bookingService
      .getBookingById(this.bookingId())
      .pipe(
        tap((booking: Booking) => {
          // Verify booking is completed and not already reviewed
          if (booking.hasOwnerReview) {
            this.snackBar.open('Već ste recenzirali ovog zakupca.', 'Zatvori', {
              duration: 5000,
              panelClass: ['snackbar-error'],
            });
            this.router.navigate(['/owner/bookings']);
            return;
          }

          // P1-7 FIX: Align with backend completion logic
          // A booking is considered completed if status is COMPLETED OR end date is in the past.
          // This matches BookingService.isBookingCompleted() on the backend.
          const isCompleted =
            booking.status === 'COMPLETED' ||
            (booking.endTime && new Date(booking.endTime) < new Date());

          if (!isCompleted) {
            this.snackBar.open('Možete recenzirati samo završene rezervacije.', 'Zatvori', {
              duration: 5000,
              panelClass: ['snackbar-error'],
            });
            this.router.navigate(['/owner/bookings']);
            return;
          }

          // P0-1 FIX: Enforce 14-day submission window on frontend
          if (booking.endTime) {
            const endDate = new Date(booking.endTime);
            const deadline = new Date(endDate.getTime() + 14 * 24 * 60 * 60 * 1000);
            if (new Date() > deadline) {
              this.snackBar.open(
                'Rok za ostavljanje recenzije je istekao (14 dana nakon završetka).',
                'Zatvori',
                {
                  duration: 5000,
                  panelClass: ['snackbar-error'],
                },
              );
              this.router.navigate(['/owner/bookings']);
              return;
            }
          }

          this.booking.set(booking);
          this.isLoading.set(false);
        }),
        catchError((error) => {
          console.error('Error loading booking:', error);
          this.snackBar.open('Greška prilikom učitavanja rezervacije.', 'Zatvori', {
            duration: 3000,
            panelClass: ['snackbar-error'],
          });
          this.router.navigate(['/owner/bookings']);
          return of(null);
        }),
      )
      .subscribe();
  }

  protected setRating(category: (typeof this.categories)[0], rating: number): void {
    category.rating.set(rating);
  }

  protected submitReview(): void {
    if (!this.canSubmit()) return;

    this.isSubmitting.set(true);

    const request: OwnerReviewRequest = {
      bookingId: this.bookingId(),
      communicationRating: this.categories[0].rating(),
      cleanlinessRating: this.categories[1].rating(),
      timelinessRating: this.categories[2].rating(),
      respectForRulesRating: this.categories[3].rating(),
      comment: this.reviewForm.value.comment || undefined,
    };

    this.reviewService
      .submitOwnerReview(request)
      .pipe(
        tap(() => {
          this.snackBar.open('Hvala! Vaša recenzija je uspešno poslata.', 'Zatvori', {
            duration: 3000,
            panelClass: ['snackbar-success'],
          });
          this.router.navigate(['/owner/bookings']);
        }),
        catchError((err) => {
          const message = err.error?.error || 'Greška prilikom slanja recenzije.';
          this.snackBar.open(message, 'Zatvori', {
            duration: 5000,
            panelClass: ['snackbar-error'],
          });
          this.isSubmitting.set(false);
          return of(null);
        }),
      )
      .subscribe();
  }

  protected canSubmit(): boolean {
    return this.categories.every((cat) => cat.rating() > 0) && !this.isSubmitting();
  }

  protected get commentLength(): number {
    return this.reviewForm.value.comment?.length || 0;
  }

  protected get commentMaxLength(): number {
    return 500;
  }

  protected get renterName(): string {
    const b = this.booking();
    if (!b) return 'Zakupac';
    const firstName = b.renter.firstName || '';
    const lastName = b.renter.lastName || '';
    return `${firstName} ${lastName}`.trim() || 'Zakupac';
  }

  protected get renterInitials(): string {
    const b = this.booking();
    if (!b) return 'Z';
    const first = b.renter.firstName?.charAt(0) || '';
    const last = b.renter.lastName?.charAt(0) || '';
    return `${first}${last}` || 'Z';
  }

  protected get renterAvatarUrl(): string {
    const b = this.booking();
    if (!b?.renter?.avatarUrl) return '';

    // Handle full URLs
    if (b.renter.avatarUrl.startsWith('http')) {
      return b.renter.avatarUrl;
    }

    // Handle relative URLs - prepend base URL
    return b.renter.avatarUrl;
  }

  protected onImageError(event: Event): void {
    // Hide broken image and show initials fallback
    const img = event.target as HTMLImageElement;
    img.style.display = 'none';

    // Update booking to clear avatarUrl so initials show
    const b = this.booking();
    if (b) {
      this.booking.set({
        ...b,
        renter: { ...b.renter, avatarUrl: undefined },
      });
    }
  }
}
