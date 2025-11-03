import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTabsModule } from '@angular/material/tabs';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';

import { Review } from '@core/models/review.model';

@Component({
  selector: 'app-owner-reviews',
  standalone: true,
  imports: [
    CommonModule,
    MatTabsModule,
    MatCardModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatChipsModule
  ],
  templateUrl: './owner-reviews.component.html',
  styleUrls: ['./owner-reviews.component.scss']
})
export class OwnerReviewsComponent implements OnInit {
  protected readonly isLoading = signal(false);
  protected readonly receivedReviews = signal<Review[]>([]);
  protected readonly givenReviews = signal<Review[]>([]);

  ngOnInit(): void {
    this.loadReviews();
  }

  private loadReviews(): void {
    this.isLoading.set(true);

    // TODO: Fetch from backend GET /api/reviews/owner
    setTimeout(() => {
      this.receivedReviews.set([]);
      this.givenReviews.set([]);
      this.isLoading.set(false);
    }, 500);
  }

  protected getRatingStars(rating: number): string[] {
    return Array(5).fill('star').map((_, i) => i < rating ? 'star' : 'star_border');
  }
}
