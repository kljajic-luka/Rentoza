import { Component, inject, signal, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { catchError, of, tap } from 'rxjs';

import { ReviewService } from '@core/services/review.service';
import { Booking } from '@core/models/booking.model';
import { OwnerReviewRequest, OWNER_REVIEW_CATEGORIES } from '@core/models/review.model';

export interface OwnerReviewDialogData {
  booking: Booking;
}

@Component({
  selector: 'app-owner-review-dialog',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatDividerModule
  ],
  templateUrl: './owner-review-dialog.component.html',
  styleUrls: ['./owner-review-dialog.component.scss']
})
export class OwnerReviewDialogComponent {
  private readonly reviewService = inject(ReviewService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(FormBuilder);
  protected readonly dialogRef = inject(MatDialogRef<OwnerReviewDialogComponent>);

  protected readonly booking: Booking;
  protected readonly isSubmitting = signal(false);

  // Create category signals with ratings
  protected readonly categories = OWNER_REVIEW_CATEGORIES.map(cat => ({
    ...cat,
    rating: signal(0)
  }));

  protected readonly reviewForm = this.fb.nonNullable.group({
    comment: ['', [Validators.maxLength(500)]]
  });

  constructor(@Inject(MAT_DIALOG_DATA) public data: OwnerReviewDialogData) {
    this.booking = data.booking;
  }

  protected setRating(category: typeof this.categories[0], rating: number): void {
    category.rating.set(rating);
  }

  protected submitReview(): void {
    if (!this.canSubmit()) return;

    this.isSubmitting.set(true);

    const request: OwnerReviewRequest = {
      bookingId: Number(this.booking.id),
      communicationRating: this.categories[0].rating(),
      cleanlinessRating: this.categories[1].rating(),
      timelinessRating: this.categories[2].rating(),
      respectForRulesRating: this.categories[3].rating(),
      comment: this.reviewForm.value.comment || undefined
    };

    this.reviewService.submitOwnerReview(request).pipe(
      tap(() => {
        this.snackBar.open('Hvala! Vaša recenzija je uspešno poslata.', 'Zatvori', {
          duration: 3000,
          panelClass: ['snackbar-success']
        });
        this.dialogRef.close(true); // Return true to indicate success
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

  protected get renterName(): string {
    const firstName = this.booking.renter.firstName || '';
    const lastName = this.booking.renter.lastName || '';
    return `${firstName} ${lastName}`.trim() || 'Zakupac';
  }
}