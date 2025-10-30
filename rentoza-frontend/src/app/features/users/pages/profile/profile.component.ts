import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';
import { Observable, map, shareReplay } from 'rxjs';

import { ProfileReview, UserProfileDetails } from '@core/models/user.model';
import { UserRole } from '@core/models/user-role.type';
import { UserService } from '@core/services/user.service';

@Component({
  selector: 'app-profile-page',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatButtonModule,
    MatIconModule,
    MatDividerModule,
    FlexLayoutModule
  ],
  templateUrl: './profile.component.html',
  styleUrls: ['./profile.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProfileComponent {
  private readonly userService = inject(UserService);

  private readonly roleLabels: Record<UserRole, { primary: string; secondary: string; reviews: string }> = {
    OWNER: {
      primary: 'Broj iznajmljivanja',
      secondary: 'Broj putovanja',
      reviews: 'Recenzije od vozača'
    },
    USER: {
      primary: 'Broj putovanja',
      secondary: 'Broj iznajmljivanja',
      reviews: 'Recenzije od domaćina'
    },
    ADMIN: {
      primary: 'Broj putovanja',
      secondary: 'Broj iznajmljivanja',
      reviews: 'Recenzije'
    }
  } as const;

  protected readonly starRange = [0, 1, 2, 3, 4];

  readonly profile$: Observable<ProfileViewModel> = this.userService.getProfileDetails().pipe(
    map((profile) => {
      const labels = this.roleLabels[profile.role] ?? this.roleLabels.USER;
      const highlightRole = profile.role === 'OWNER' ? profile.stats.hostedTrips : profile.stats.completedTrips;
      const secondaryValue = profile.role === 'OWNER' ? profile.stats.completedTrips : profile.stats.hostedTrips;

      const metrics: ProfileMetric[] = [
        {
          label: labels.primary,
          value: highlightRole.toString(),
          accent: true
        },
        {
          label: labels.secondary,
          value: secondaryValue.toString()
        },
        {
          label: 'Prosečna ocena',
          value: profile.averageRating.toFixed(1)
        }
      ];

      const initials = `${profile.firstName?.charAt(0) ?? ''}${profile.lastName?.charAt(0) ?? ''}`
        .toUpperCase()
        .trim() || 'U';

      return {
        ...profile,
        initials,
        metrics,
        reviewSectionTitle: labels.reviews,
        hasReviews: profile.reviews.length > 0
      } satisfies ProfileViewModel;
    }),
    shareReplay({ bufferSize: 1, refCount: true })
  );

  protected trackByReview(_index: number, review: ProfileReview): string {
    return review.id;
  }
}

interface ProfileMetric {
  label: string;
  value: string;
  accent?: boolean;
}

interface ProfileViewModel extends UserProfileDetails {
  initials: string;
  metrics: ProfileMetric[];
  reviewSectionTitle: string;
  hasReviews: boolean;
}
