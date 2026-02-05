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
import { UserBooking } from '@core/models/booking.model';
import { RenterReviewRequest, REVIEW_CATEGORIES } from '@core/models/review.model';
import { canReviewBooking } from '@core/utils/booking.utils';

@Component({
  selector: 'app-add-review',
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
    MatDividerModule
  ],
  templateUrl: './add-review.component.html',
  styleUrls: ['./add-review.component.scss']
})
export class AddReviewComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly reviewService = inject(ReviewService);
  private readonly bookingService = inject(BookingService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(FormBuilder);

  protected readonly bookingId = signal<number>(0);
  protected readonly booking = signal<UserBooking | null>(null);
  protected readonly isLoading = signal(true);
  protected readonly isSubmitting = signal(false);

  // Create category signals with ratings
  protected readonly categories = REVIEW_CATEGORIES.map(cat => ({
    ...cat,
    rating: signal(0)
  }));

  protected readonly reviewForm = this.fb.nonNullable.group({
    comment: ['', [Validators.maxLength(500)]]
  });

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.bookingId.set(+id);
      this.loadBooking();
    } else {
      this.snackBar.open('Nevažeći ID rezervacije.', 'Zatvori', {
        duration: 3000,
        panelClass: ['snackbar-error']
      });
      this.router.navigate(['/bookings']);
    }
  }

  private loadBooking(): void {
    this.isLoading.set(true);

    this.bookingService.getMyBookings().pipe(
      tap((bookings: UserBooking[]) => {
        const foundBooking = bookings.find(b => b.id === this.bookingId());

        if (!foundBooking) {
          this.snackBar.open('Rezervacija nije pronađena.', 'Zatvori', {
            duration: 3000,
            panelClass: ['snackbar-error']
          });
          this.router.navigate(['/bookings']);
          return;
        }

        // Use unified completion check to validate review eligibility
        if (!canReviewBooking(foundBooking)) {
          const message = foundBooking.hasReview
            ? 'Već ste recenzirali ovu rezervaciju.'
            : 'Možete recenzirati samo završene rezervacije.';

          this.snackBar.open(message, 'Zatvori', {
            duration: 5000,
            panelClass: ['snackbar-error']
          });
          this.router.navigate(['/bookings']);
          return;
        }

        this.booking.set(foundBooking);
        this.isLoading.set(false);
      }),
      catchError((error) => {
        console.error('Error loading booking:', error);
        this.snackBar.open('Greška prilikom učitavanja rezervacije.', 'Zatvori', {
          duration: 3000,
          panelClass: ['snackbar-error']
        });
        this.router.navigate(['/bookings']);
        return of([]);
      })
    ).subscribe();
  }

  protected setRating(category: typeof this.categories[0], rating: number): void {
    category.rating.set(rating);
  }

  protected submitReview(): void {
    if (!this.canSubmit()) return;

    this.isSubmitting.set(true);

    const request: RenterReviewRequest = {
      bookingId: this.bookingId(),
      cleanlinessRating: this.categories[0].rating(),
      maintenanceRating: this.categories[1].rating(),
      communicationRating: this.categories[2].rating(),
      convenienceRating: this.categories[3].rating(),
      accuracyRating: this.categories[4].rating(),
      comment: this.reviewForm.value.comment || undefined
    };

    this.reviewService.submitRenterReview(request).pipe(
      tap(() => {
        this.snackBar.open('Hvala! Vaša recenzija je uspešno poslata.', 'Zatvori', {
          duration: 3000,
          panelClass: ['snackbar-success']
        });
        this.router.navigate(['/bookings']);
      }),
      catchError((err) => {
        const message = err.error?.error || 'Greška prilikom slanja recenzije.';
        this.snackBar.open(message, 'Zatvori', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
        this.isSubmitting.set(false);
        return of(null);
      })
    ).subscribe();
  }

  protected canSubmit(): boolean {
    return this.categories.every(cat => cat.rating() > 0) && !this.isSubmitting();
  }

  protected get commentLength(): number {
    return this.reviewForm.value.comment?.length || 0;
  }

  protected get commentMaxLength(): number {
    return 500;
  }
}
