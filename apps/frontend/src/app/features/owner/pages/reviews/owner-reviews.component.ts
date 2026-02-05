import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTabsModule } from '@angular/material/tabs';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { forkJoin, catchError, of, filter, take } from 'rxjs';

import { Review } from '@core/models/review.model';
import { ReviewService } from '@core/services/review.service';
import { AuthService } from '@core/auth/auth.service';

@Component({
  selector: 'app-owner-reviews',
  standalone: true,
  imports: [
    CommonModule,
    MatTabsModule,
    MatCardModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatSnackBarModule
  ],
  templateUrl: './owner-reviews.component.html',
  styleUrls: ['./owner-reviews.component.scss']
})
export class OwnerReviewsComponent implements OnInit {
  private readonly reviewService = inject(ReviewService);
  private readonly authService = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly isLoading = signal(false);
  protected readonly receivedReviews = signal<Review[]>([]);
  protected readonly givenReviews = signal<Review[]>([]);
  protected readonly starRange = [0, 1, 2, 3, 4];

  ngOnInit(): void {
    this.loadReviews();
  }

  private loadReviews(): void {
    this.isLoading.set(true);

    // Get current user email
    this.authService.currentUser$
      .pipe(
        filter((user): user is NonNullable<typeof user> => {
          const isValid = user !== null && !!(user.email || user.id);
          return isValid;
        }),
        take(1)
      )
      .subscribe({
        next: (user) => {
          const email = user.email || user.id;

          // Fetch both received and given reviews in parallel
          forkJoin({
            received: this.reviewService.getReceivedReviews(email).pipe(
              catchError((error) => {
                console.error('Error loading received reviews:', error);
                return of([]);
              })
            ),
            given: this.reviewService.getReviewsFromOwner(email).pipe(
              catchError((error) => {
                console.error('Error loading given reviews:', error);
                return of([]);
              })
            )
          }).subscribe({
            next: ({ received, given }) => {
              this.receivedReviews.set(received);
              this.givenReviews.set(given);
              this.isLoading.set(false);
            },
            error: (error) => {
              console.error('Error loading reviews:', error);
              this.snackBar.open('Greška pri učitavanju recenzija', 'Zatvori', { duration: 3000 });
              this.isLoading.set(false);
            }
          });
        },
        error: (error) => {
          console.error('Error getting user:', error);
          this.snackBar.open('Greška pri učitavanju korisničkih podataka', 'Zatvori', { duration: 3000 });
          this.isLoading.set(false);
        }
      });
  }

  protected getRatingStars(rating: number): string[] {
    return Array(5).fill('star').map((_, i) => i < rating ? 'star' : 'star_border');
  }

  protected getDisplayName(review: Review, isReviewer: boolean = true): string {
    const first = isReviewer ? review.reviewerFirstName : review.revieweeFirstName;
    const last = isReviewer ? review.reviewerLastName : review.revieweeLastName;

    if (!first && !last) {
      return 'Anonimni korisnik';
    }

    const initial = last ? `${last.charAt(0).toUpperCase()}.` : '';
    return `${first ?? ''} ${initial}`.trim();
  }

  protected formatDate(dateString: string): string {
    return new Date(dateString).toLocaleDateString('sr-RS', {
      day: 'numeric',
      month: 'long',
      year: 'numeric'
    });
  }
}
