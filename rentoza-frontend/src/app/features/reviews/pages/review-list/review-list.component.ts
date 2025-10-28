import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';
import { Observable } from 'rxjs';

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
    FlexLayoutModule
  ],
  templateUrl: './review-list.component.html',
  styleUrls: ['./review-list.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ReviewListComponent {
  private readonly reviewService = inject(ReviewService);

  readonly reviews$: Observable<Review[]> = this.reviewService.getRecentReviews();

  trackByReviewId(_index: number, review: Review): string {
    return review.id;
  }
}
