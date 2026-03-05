import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  ValidationErrors,
  FormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { LoadingSkeletonComponent } from '@shared/components/loading-skeleton/loading-skeleton.component';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';
import {
  Observable,
  map,
  shareReplay,
  Subject,
  switchMap,
  startWith,
  catchError,
  of,
  tap,
} from 'rxjs';

import { ProfileReview, UserProfileDetails, UpdateProfileRequest } from '@core/models/user.model';
import { UserRole } from '@core/models/user-role.type';
import { UserService } from '@core/services/user.service';
import { AuthService } from '@core/auth/auth.service';
import { ProfilePictureUploaderComponent } from '@shared/components/profile-picture-uploader/profile-picture-uploader.component';
import { VerificationBadgeComponent } from '@shared/components/verification-badge/verification-badge.component';

const optionalMinLengthValidator = (minLength: number) => {
  return (control: AbstractControl): ValidationErrors | null => {
    const rawValue = control.value ?? '';
    const value = typeof rawValue === 'string' ? rawValue.trim() : String(rawValue).trim();
    if (!value) {
      return null;
    }
    return value.length >= minLength
      ? null
      : { minlength: { requiredLength: minLength, actualLength: value.length } };
  };
};

@Component({
  selector: 'app-profile-page',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatCardModule,
    MatProgressSpinnerModule,
    LoadingSkeletonComponent,
    MatButtonModule,
    MatIconModule,
    MatDividerModule,
    MatFormFieldModule,
    MatInputModule,
    MatDialogModule,
    MatSnackBarModule,
    FlexLayoutModule,
    ProfilePictureUploaderComponent,
    VerificationBadgeComponent,
  ],
  templateUrl: './profile.component.html',
  styleUrls: ['./profile.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfileComponent {
  private readonly userService = inject(UserService);
  private readonly authService = inject(AuthService);
  private readonly fb = inject(FormBuilder);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly googlePlaceholderLastName = 'GooglePlaceholder';

  private readonly refreshProfile$ = new Subject<void>();

  // Edit mode state
  protected readonly isEditMode = signal(false);
  protected readonly isSaving = signal(false);
  protected readonly isGooglePlaceholder = signal(false);

  // DOB field: max date is 21 years ago (minimum age requirement)
  protected readonly maxDateOfBirth = this.calculateMaxDateOfBirth();

  private calculateMaxDateOfBirth(): string {
    const today = new Date();
    const minAge = 21;
    const maxDate = new Date(today.getFullYear() - minAge, today.getMonth(), today.getDate());
    return maxDate.toISOString().split('T')[0];
  }

  // Edit form (avatarUrl removed - now using ProfilePictureUploader component)
  protected readonly editForm = this.fb.group({
    phone: ['', [Validators.pattern(/^\+?[0-9]{8,15}$/)]],
    bio: ['', [Validators.maxLength(300)]],
    lastName: [
      { value: '', disabled: true },
      [optionalMinLengthValidator(3), Validators.maxLength(50)],
    ],
    dateOfBirth: [''],
  });

  private readonly roleLabels: Record<UserRole, { primary: string; reviews: string }> = {
    OWNER: {
      primary: 'Broj iznajmljivanja',
      reviews: 'Recenzije od vozača',
    },
    USER: {
      primary: 'Broj putovanja',
      reviews: 'Recenzije od domaćina',
    },
    ADMIN: {
      primary: 'Broj putovanja',
      reviews: 'Recenzije',
    },
  } as const;

  protected readonly starRange = [0, 1, 2, 3, 4];

  readonly profile$: Observable<ProfileViewModel> = this.refreshProfile$.pipe(
    startWith(void 0),
    switchMap(() => this.userService.getProfileDetails()),
    map((profile) => {
      const labels = this.roleLabels[profile.role] ?? this.roleLabels.USER;
      const highlightRole =
        profile.role === 'OWNER' ? profile.stats.hostedTrips : profile.stats.completedTrips;

      const metrics: ProfileMetric[] = [
        {
          label: labels.primary,
          value: highlightRole.toString(),
          accent: true,
        },
        {
          label: 'Prosečna ocena',
          value: profile.averageRating.toFixed(1),
        },
      ];

      const initials =
        `${profile.firstName?.charAt(0) ?? ''}${profile.lastName?.charAt(0) ?? ''}`
          .toUpperCase()
          .trim() || 'U';

      return {
        ...profile,
        initials,
        metrics,
        reviewSectionTitle: labels.reviews,
        hasReviews: profile.reviews.length > 0,
      } satisfies ProfileViewModel;
    }),
    shareReplay({ bufferSize: 1, refCount: true }),
  );

  protected trackByReview(_index: number, review: ProfileReview): string {
    return review.id;
  }

  /**
   * Enter edit mode and populate form with current profile data
   */
  protected enterEditMode(profile: UserProfileDetails): void {
    this.isEditMode.set(true);
    const isPlaceholder = profile.lastName === this.googlePlaceholderLastName;
    this.isGooglePlaceholder.set(isPlaceholder);

    this.editForm.patchValue({
      phone: profile.phone ?? '',
      bio: profile.bio ?? '',
      dateOfBirth: profile.dateOfBirth ?? '',
    });

    const lastNameControl = this.editForm.get('lastName');
    if (lastNameControl) {
      if (isPlaceholder) {
        lastNameControl.enable({ emitEvent: false });
        lastNameControl.setValue('', { emitEvent: false });
      } else {
        lastNameControl.disable({ emitEvent: false });
        lastNameControl.setValue(profile.lastName ?? '', { emitEvent: false });
      }
      lastNameControl.markAsPristine();
      lastNameControl.markAsUntouched();
    }

    // DOB can only be edited if not already verified via license OCR
    const dobControl = this.editForm.get('dateOfBirth');
    if (dobControl) {
      if (profile.dobVerified) {
        dobControl.disable({ emitEvent: false });
      } else {
        dobControl.enable({ emitEvent: false });
      }
    }
  }

  /**
   * Cancel edit mode and reset form
   */
  protected cancelEdit(): void {
    this.isEditMode.set(false);
    this.isGooglePlaceholder.set(false);
    this.editForm.reset();
    const lastNameControl = this.editForm.get('lastName');
    lastNameControl?.disable({ emitEvent: false });
  }

  /**
   * Save profile changes - only updates allowed fields
   */
  protected saveProfile(): void {
    if (this.editForm.invalid || this.isSaving()) {
      return;
    }

    this.isSaving.set(true);
    const request: UpdateProfileRequest = {
      phone: this.editForm.value.phone || undefined,
      bio: this.editForm.value.bio || undefined,
    };

    // Include DOB if field is enabled (not verified via OCR) and has value
    const dobControl = this.editForm.get('dateOfBirth');
    if (dobControl?.enabled && this.editForm.value.dateOfBirth) {
      request.dateOfBirth = this.editForm.value.dateOfBirth;
    }

    const lastNameControl = this.editForm.get('lastName');
    if (this.isGooglePlaceholder() && lastNameControl?.enabled) {
      lastNameControl.updateValueAndValidity();
      if (lastNameControl.invalid) {
        this.isSaving.set(false);
        this.snackBar.open('Prezime mora imati najmanje 3 karaktera.', 'Zatvori', {
          duration: 4000,
          panelClass: ['snackbar-error'],
        });
        return;
      }
      const trimmedLastName = (lastNameControl.value ?? '').trim();
      if (trimmedLastName && trimmedLastName !== this.googlePlaceholderLastName) {
        request.lastName = trimmedLastName;
      }
    }

    this.userService
      .updateMyProfile(request)
      .pipe(
        tap(() => {
          this.snackBar.open('Profil uspešno ažuriran.', 'Zatvori', {
            duration: 3000,
            panelClass: ['snackbar-success'],
          });
          this.isEditMode.set(false);
          this.isSaving.set(false);
          this.refreshProfile$.next();
        }),
        catchError((error) => {
          const message = error.error?.error || 'Došlo je do greške prilikom ažuriranja profila.';
          this.snackBar.open(message, 'Zatvori', {
            duration: 5000,
            panelClass: ['snackbar-error'],
          });
          this.isSaving.set(false);
          return of(null);
        }),
      )
      .subscribe();
  }

  /**
   * Show information modal when user tries to edit restricted fields
   */
  protected showRestrictedFieldInfo(): void {
    this.snackBar.open(
      'Promena ličnih podataka zahteva verifikaciju identiteta. Kontaktirajte podršku.',
      'Zatvori',
      {
        duration: 6000,
        panelClass: ['snackbar-info'],
      },
    );
  }

  /**
   * Handle successful avatar upload from ProfilePictureUploader component.
   * Updates global user state and refreshes profile data.
   */
  protected onAvatarUploaded(newAvatarUrl: string): void {
    // Update the global user state in AuthService
    this.authService.updateCurrentUserAvatar(newAvatarUrl);

    // Show success notification
    this.snackBar.open('Profilna slika uspešno postavljena.', 'Zatvori', {
      duration: 3000,
      panelClass: ['snackbar-success'],
    });

    // Refresh profile to get updated data
    this.refreshProfile$.next();
  }

  /**
   * Handle avatar upload error from ProfilePictureUploader component.
   */
  protected onAvatarUploadError(errorMessage: string): void {
    this.snackBar.open(errorMessage, 'Zatvori', {
      duration: 5000,
      panelClass: ['snackbar-error'],
    });
  }

  /**
   * Handle avatar deletion from ProfilePictureUploader component.
   */
  protected onAvatarDeleted(): void {
    // Update the global user state in AuthService
    this.authService.updateCurrentUserAvatar(null);

    // Show success notification
    this.snackBar.open('Profilna slika obrisana.', 'Zatvori', {
      duration: 3000,
      panelClass: ['snackbar-success'],
    });

    // Refresh profile to get updated data
    this.refreshProfile$.next();
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
