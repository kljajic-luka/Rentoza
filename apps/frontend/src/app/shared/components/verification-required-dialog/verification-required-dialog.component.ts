import { Component, inject, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

/**
 * Data passed to the verification required dialog.
 */
export interface VerificationRequiredDialogData {
  /** Why booking is blocked (from eligibility check) */
  blockedReason: string | null;
  /** Serbian user-facing message */
  messageSr: string | null;
  /** English user-facing message */
  messageEn: string | null;
  /** Car ID to return to after verification */
  carId: number | string;
  /** Car name for display */
  carName: string;
  /** Days until license expiry (if applicable) */
  daysUntilExpiry?: number | null;
  /** Whether license expires before trip end */
  licenseExpiresBeforeTripEnd?: boolean;
}

/**
 * Dialog result from verification required dialog.
 */
export type VerificationRequiredDialogResult = 'verify' | 'browse' | 'cancel';

/**
 * VerificationRequiredDialogComponent
 *
 * A modal dialog shown when a user attempts to book a car without completing
 * driver license verification. Provides clear guidance and actions:
 *
 * - Explains why verification is needed
 * - Shows car name they're trying to book
 * - CTA to verification page with return URL
 * - Option to browse other cars
 * - Cancel to close dialog
 *
 * @example
 * ```typescript
 * const dialogRef = this.dialog.open(VerificationRequiredDialogComponent, {
 *   data: {
 *     blockedReason: 'LICENSE_NOT_VERIFIED',
 *     messageSr: 'Potrebna je verifikacija vozačke dozvole.',
 *     carId: car.id,
 *     carName: car.name,
 *   },
 *   width: '450px',
 *   disableClose: false,
 * });
 *
 * dialogRef.afterClosed().subscribe((result) => {
 *   if (result === 'verify') {
 *     this.router.navigate(['/verify-license'], { queryParams: { returnUrl: `/cars/${car.id}` } });
 *   }
 * });
 * ```
 */
@Component({
  selector: 'app-verification-required-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <div class="verification-dialog">
      <!-- Header with icon -->
      <div class="dialog-header">
        <div class="header-icon" [class]="iconClass()">
          <mat-icon>{{ headerIcon() }}</mat-icon>
        </div>
        <h2 mat-dialog-title>{{ dialogTitle() }}</h2>
      </div>

      <!-- Content -->
      <mat-dialog-content>
        <p class="message">{{ userMessage() }}</p>

        @if (data.carName) {
        <div class="car-info">
          <mat-icon>directions_car</mat-icon>
          <span>{{ data.carName }}</span>
        </div>
        } @if (showExpiryWarning()) {
        <div class="expiry-warning">
          <mat-icon>warning</mat-icon>
          <span>Vaša vozačka dozvola ističe za {{ data.daysUntilExpiry }} dana.</span>
        </div>
        }

        <div class="benefits">
          <h4>Verifikacija traje samo 2 minuta:</h4>
          <ul>
            <li>
              <mat-icon>photo_camera</mat-icon>
              <span>Fotografišite vozačku dozvolu (prednja i zadnja strana)</span>
            </li>
            <li>
              <mat-icon>schedule</mat-icon>
              <span>Automatska provera za ~30 sekundi</span>
            </li>
            <li>
              <mat-icon>verified_user</mat-icon>
              <span>Jednom verifikovano - važi za sva buduća iznajmljivanja</span>
            </li>
          </ul>
        </div>
      </mat-dialog-content>

      <!-- Actions -->
      <mat-dialog-actions align="end">
        <button mat-button color="basic" (click)="cancel()" [disabled]="isNavigating()">
          Otkazi
        </button>
        <button mat-button color="basic" (click)="browseCars()" [disabled]="isNavigating()">
          <mat-icon>search</mat-icon>
          Pregledaj vozila
        </button>
        <button
          mat-flat-button
          color="primary"
          class="verify-btn"
          (click)="goToVerification()"
          [disabled]="isNavigating()"
        >
          @if (isNavigating()) {
          <mat-spinner diameter="18" color="accent"></mat-spinner>
          } @else {
          <ng-container>
            <mat-icon>verified_user</mat-icon>
            Verifikuj dozvolu
          </ng-container>
          }
        </button>
      </mat-dialog-actions>
    </div>
  `,
  styles: [
    `
      .verification-dialog {
        padding: 8px;
      }

      .dialog-header {
        display: flex;
        flex-direction: column;
        align-items: center;
        margin-bottom: 16px;
      }

      .header-icon {
        width: 64px;
        height: 64px;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        margin-bottom: 12px;

        mat-icon {
          font-size: 32px;
          width: 32px;
          height: 32px;
        }

        &.icon-warning {
          background: color-mix(in srgb, var(--mat-sys-warning) 15%, transparent);
          color: var(--mat-sys-warning);
        }

        &.icon-error {
          background: color-mix(in srgb, var(--mat-sys-error) 15%, transparent);
          color: var(--mat-sys-error);
        }

        &.icon-info {
          background: color-mix(in srgb, var(--mat-sys-primary) 15%, transparent);
          color: var(--mat-sys-primary);
        }
      }

      h2[mat-dialog-title] {
        text-align: center;
        margin: 0;
        font-size: 1.25rem;
        font-weight: 600;
        color: var(--mat-sys-on-surface);
      }

      mat-dialog-content {
        padding-top: 8px;
      }

      .message {
        text-align: center;
        color: var(--mat-sys-on-surface-variant);
        margin-bottom: 16px;
        font-size: 0.9375rem;
        line-height: 1.5;
      }

      .car-info {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 12px 16px;
        background: var(--mat-sys-surface-container);
        border-radius: 8px;
        margin-bottom: 16px;

        mat-icon {
          color: var(--mat-sys-on-surface-variant);
        }

        span {
          font-weight: 500;
          color: var(--mat-sys-on-surface);
        }
      }

      .expiry-warning {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 12px 16px;
        background: color-mix(in srgb, var(--mat-sys-warning) 15%, transparent);
        border: 1px solid color-mix(in srgb, var(--mat-sys-warning) 30%, transparent);
        border-radius: 8px;
        margin-bottom: 16px;
        color: var(--mat-sys-warning);

        mat-icon {
          font-size: 20px;
          width: 20px;
          height: 20px;
        }

        span {
          font-size: 0.875rem;
        }
      }

      .benefits {
        background: var(--mat-sys-surface-container-low);
        border-radius: 8px;
        padding: 16px;

        h4 {
          margin: 0 0 12px 0;
          font-size: 0.875rem;
          font-weight: 600;
          color: var(--mat-sys-on-surface);
        }

        ul {
          margin: 0;
          padding: 0;
          list-style: none;
        }

        li {
          display: flex;
          align-items: center;
          gap: 10px;
          padding: 8px 0;
          color: var(--mat-sys-on-surface-variant);
          font-size: 0.875rem;

          mat-icon {
            color: var(--mat-sys-tertiary);
            font-size: 18px;
            width: 18px;
            height: 18px;
          }
        }
      }

      mat-dialog-actions {
        padding: 16px 0 0 0;
        margin: 0;
        gap: 8px;

        button {
          min-width: auto;
        }

        .verify-btn {
          min-width: 160px;

          mat-icon {
            margin-right: 4px;
          }

          mat-spinner {
            margin: 0 auto;
          }
        }
      }

      @media (max-width: 480px) {
        mat-dialog-actions {
          flex-direction: column;

          button {
            width: 100%;
          }
        }
      }
    `,
  ],
})
export class VerificationRequiredDialogComponent implements OnInit {
  private readonly router = inject(Router);
  readonly dialogRef = inject(MatDialogRef<VerificationRequiredDialogComponent>);
  readonly data = inject<VerificationRequiredDialogData>(MAT_DIALOG_DATA);

  /** Prevents double-click during navigation */
  readonly isNavigating = signal(false);

  /**
   * Computed icon class based on blocked reason.
   */
  readonly iconClass = signal('icon-warning');

  /**
   * Computed header icon based on status.
   */
  readonly headerIcon = signal('shield');

  /**
   * Dialog title based on the verification status.
   */
  readonly dialogTitle = signal('Verifikacija potrebna');

  ngOnInit(): void {
    this.computeDisplayState();
  }

  /**
   * Computes display state based on blocked reason.
   */
  private computeDisplayState(): void {
    const reason = this.data.blockedReason?.toUpperCase() ?? '';

    if (reason.includes('EXPIRED') || this.data.licenseExpiresBeforeTripEnd) {
      this.iconClass.set('icon-error');
      this.headerIcon.set('event_busy');
      this.dialogTitle.set('Vozačka dozvola istekla');
    } else if (reason.includes('REJECTED')) {
      this.iconClass.set('icon-error');
      this.headerIcon.set('cancel');
      this.dialogTitle.set('Verifikacija odbijena');
    } else if (reason.includes('PENDING')) {
      this.iconClass.set('icon-info');
      this.headerIcon.set('hourglass_empty');
      this.dialogTitle.set('Verifikacija u toku');
    } else if (reason.includes('SUSPENDED')) {
      this.iconClass.set('icon-error');
      this.headerIcon.set('block');
      this.dialogTitle.set('Nalog suspendovan');
    } else {
      // Default: NOT_STARTED or LICENSE_NOT_VERIFIED
      this.iconClass.set('icon-warning');
      this.headerIcon.set('shield');
      this.dialogTitle.set('Verifikacija potrebna');
    }
  }

  /**
   * User-facing message to display.
   */
  userMessage(): string {
    // Prefer Serbian message from backend if available
    if (this.data.messageSr) {
      return this.data.messageSr;
    }

    const reason = this.data.blockedReason?.toUpperCase() ?? '';

    if (reason.includes('EXPIRED')) {
      return 'Vaša vozačka dozvola je istekla. Molimo obnovite dozvolu i ponovo je verifikujte.';
    }
    if (reason.includes('REJECTED')) {
      return 'Vaša prethodna verifikacija je odbijena. Molimo ponovo pošaljite jasne fotografije vozačke dozvole.';
    }
    if (reason.includes('PENDING')) {
      return 'Vaša verifikacija je u toku. Proverite status na stranici za verifikaciju.';
    }
    if (reason.includes('SUSPENDED')) {
      return 'Vaš nalog je suspendovan. Kontaktirajte podršku za više informacija.';
    }
    if (this.data.licenseExpiresBeforeTripEnd) {
      return 'Vaša vozačka dozvola ističe pre završetka putovanja. Molimo obnovite dozvolu.';
    }

    return 'Da biste rezervisali vozilo, potrebno je da verifikujete svoju vozačku dozvolu.';
  }

  /**
   * Show expiry warning badge.
   */
  showExpiryWarning(): boolean {
    return (
      this.data.daysUntilExpiry != null &&
      this.data.daysUntilExpiry > 0 &&
      this.data.daysUntilExpiry <= 30
    );
  }

  /**
   * Navigate to verification page with return URL.
   */
  goToVerification(): void {
    this.isNavigating.set(true);

    const returnUrl = this.data.carId ? `/cars/${this.data.carId}` : '/cars';

    this.dialogRef.close('verify' satisfies VerificationRequiredDialogResult);

    // Navigate after dialog closes to prevent animation glitch
    setTimeout(() => {
      this.router.navigate(['/verify-license'], {
        queryParams: { returnUrl },
      });
    }, 150);
  }

  /**
   * Navigate to browse cars.
   */
  browseCars(): void {
    this.isNavigating.set(true);
    this.dialogRef.close('browse' satisfies VerificationRequiredDialogResult);

    setTimeout(() => {
      this.router.navigate(['/cars']);
    }, 150);
  }

  /**
   * Cancel and close dialog.
   */
  cancel(): void {
    this.dialogRef.close('cancel' satisfies VerificationRequiredDialogResult);
  }
}