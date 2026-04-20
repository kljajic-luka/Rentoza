import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';
import { RouterModule } from '@angular/router';
import { Observable, map, shareReplay } from 'rxjs';

import { Review } from '@core/models/review.model';
import { ReviewService } from '@core/services/review.service';

@Component({
  selector: 'app-review-list',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatButtonModule,
    MatIconModule,
    FlexLayoutModule,
    RouterModule
  ],
  templateUrl: './review-list.component.html',
  styleUrls: ['./review-list.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ReviewListComponent {
  private readonly reviewService = inject(ReviewService);

  protected readonly starRange = [0, 1, 2, 3, 4];

  readonly reviews$: Observable<ReviewViewModel[]> = this.reviewService.getRecentReviews().pipe(
    map((reviews) =>
      reviews
        .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
        .slice(0, 10)
        .map((review) => ({
          ...review,
          displayName: getDisplayName(review),
        }))
    ),
    shareReplay({ bufferSize: 1, refCount: true })
  );

  trackByReviewId(_index: number, review: ReviewViewModel): string {
    return review.id;
  }
}

type ReviewViewModel = Review & { displayName: string };

const getDisplayName = (review: Review): string => {
  const first = review.reviewerFirstName?.trim();
  const last = review.reviewerLastName?.trim();

  if (!first && !last) {
    return 'Anonimni korisnik';
  }

  const initial = last ? `${last.charAt(0).toUpperCase()}.` : '';
  return `${first ?? ''} ${initial}`.trim();
};