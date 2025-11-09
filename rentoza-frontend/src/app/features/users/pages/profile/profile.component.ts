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
    MatButtonModule,
    MatIconModule,
    MatDividerModule,
    MatFormFieldModule,
    MatInputModule,
    MatDialogModule,
    MatSnackBarModule,
    FlexLayoutModule,
  ],
  templateUrl: './profile.component.html',
  styleUrls: ['./profile.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfileComponent {
  private readonly userService = inject(UserService);
  private readonly fb = inject(FormBuilder);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly googlePlaceholderLastName = 'GooglePlaceholder';

  private readonly refreshProfile$ = new Subject<void>();

  // Edit mode state
  protected readonly isEditMode = signal(false);
  protected readonly isSaving = signal(false);
  protected readonly isGooglePlaceholder = signal(false);

  // Edit form
  protected readonly editForm = this.fb.group({
    phone: ['', [Validators.pattern(/^[0-9]{8,15}$/)]],
    avatarUrl: ['', [Validators.maxLength(500)]],
    bio: ['', [Validators.maxLength(300)]],
    lastName: [{ value: '', disabled: true }, [optionalMinLengthValidator(3), Validators.maxLength(50)]],
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
    shareReplay({ bufferSize: 1, refCount: true })
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
      avatarUrl: profile.avatarUrl ?? '',
      bio: profile.bio ?? '',
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
      avatarUrl: this.editForm.value.avatarUrl || undefined,
      bio: this.editForm.value.bio || undefined,
    };

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
        })
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
      }
    );
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
