import { Component, OnInit, OnDestroy, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subject, takeUntil } from 'rxjs';

import { RenterVerificationService } from '@core/services/renter-verification.service';
import {
  DriverLicenseStatus,
  RenterVerificationProfile,
  getStatusLabelSr,
  getStatusClass,
  getStatusIcon,
} from '@core/models/renter-verification.model';

/**
 * Verification Badge Component
 *
 * A compact badge displayed in the profile header/sidebar showing the user's
 * driver license verification status with a CTA to the verification page.
 *
 * Usage:
 * ```html
 * <app-verification-badge></app-verification-badge>
 * ```
 *
 * States:
 * - NOT_STARTED: "Verifikujte vozačku" - primary CTA
 * - PENDING_REVIEW: "Na čekanju" - info badge with spinner
 * - APPROVED: "Verifikovan" - success badge
 * - REJECTED: "Odbijeno" - warning badge with CTA
 * - EXPIRED: "Isteklo" - warning badge with CTA
 * - SUSPENDED: "Suspendovano" - error badge
 */
@Component({
  selector: 'app-verification-badge',
  standalone: true,
  imports: [CommonModule, RouterModule, MatIconModule, MatButtonModule, MatTooltipModule],
  template: `
    @if (loaded()) {
    <a
      class="verification-badge {{ badgeClass() }}"
      [routerLink]="routerLink()"
      [matTooltip]="tooltipText()"
      matTooltipPosition="below"
    >
      <mat-icon class="badge-icon">{{ badgeIcon() }}</mat-icon>
      <span class="badge-label">{{ badgeLabel() }}</span>
      @if (showSpinner()) {
      <span class="badge-spinner"></span>
      }
    </a>
    }
  `,
  styles: [
    `
      .verification-badge {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        padding: 6px 12px;
        border-radius: 20px;
        font-size: 0.8125rem;
        font-weight: 500;
        text-decoration: none;
        transition: all 0.2s ease;
        cursor: pointer;

        &:hover {
          transform: translateY(-1px);
          box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
        }

        .badge-icon {
          font-size: 16px;
          width: 16px;
          height: 16px;
        }

        .badge-label {
          white-space: nowrap;
        }

        .badge-spinner {
          width: 12px;
          height: 12px;
          border: 2px solid currentColor;
          border-top-color: transparent;
          border-radius: 50%;
          animation: spin 1s linear infinite;
        }

        // Status variants
        &.badge-not-started {
          background: var(--brand-primary);
          color: white;

          &:hover {
            background: var(--primary-dark, #1565c0);
          }
        }

        &.badge-pending {
          background: var(--warning-bg, #fff3e0);
          color: var(--warning-color, #ef6c00);
          border: 1px solid var(--warning-border, #ffcc80);
        }

        &.badge-approved {
          background: var(--success-bg, #e8f5e9);
          color: var(--success-color, #2e7d32);
          border: 1px solid var(--success-border, #a5d6a7);
        }

        &.badge-rejected,
        &.badge-expired {
          background: var(--warn-bg, #ffebee);
          color: var(--warn-color, #c62828);
          border: 1px solid var(--warn-border, #ef9a9a);
        }

        &.badge-suspended {
          background: var(--warn-bg, #ffebee);
          color: var(--warn-color, #c62828);
          border: 1px solid var(--warn-border, #ef9a9a);
          cursor: not-allowed;
          pointer-events: none;
          opacity: 0.8;
        }
      }

      @keyframes spin {
        from {
          transform: rotate(0deg);
        }
        to {
          transform: rotate(360deg);
        }
      }

      // Responsive - hide label on very small screens
      @media (max-width: 400px) {
        .verification-badge {
          padding: 6px 8px;

          .badge-label {
            display: none;
          }
        }
      }
    `,
  ],
})
export class VerificationBadgeComponent implements OnInit, OnDestroy {
  private readonly verificationService = inject(RenterVerificationService);
  private readonly destroy$ = new Subject<void>();

  // ============================================================================
  // STATE
  // ============================================================================

  readonly status = signal<DriverLicenseStatus>('NOT_STARTED');
  readonly loaded = signal<boolean>(false);

  // ============================================================================
  // COMPUTED
  // ============================================================================

  readonly badgeIcon = computed(() => {
    const s = this.status();
    return getStatusIcon(s);
  });

  readonly badgeLabel = computed(() => {
    const s = this.status();
    switch (s) {
      case 'NOT_STARTED':
        return 'Verifikujte';
      case 'PENDING_REVIEW':
        return 'Na čekanju';
      case 'APPROVED':
        return 'Verifikovano';
      case 'REJECTED':
        return 'Odbijeno';
      case 'EXPIRED':
        return 'Isteklo';
      case 'SUSPENDED':
        return 'Suspendovano';
      default:
        return 'Status';
    }
  });

  readonly badgeClass = computed(() => {
    const s = this.status();
    switch (s) {
      case 'NOT_STARTED':
        return 'badge-not-started';
      case 'PENDING_REVIEW':
        return 'badge-pending';
      case 'APPROVED':
        return 'badge-approved';
      case 'REJECTED':
        return 'badge-rejected';
      case 'EXPIRED':
        return 'badge-expired';
      case 'SUSPENDED':
        return 'badge-suspended';
      default:
        return '';
    }
  });

  readonly tooltipText = computed(() => {
    const s = this.status();
    switch (s) {
      case 'NOT_STARTED':
        return 'Kliknite da verifikujete vozačku dozvolu';
      case 'PENDING_REVIEW':
        return 'Vaša verifikacija je u toku. Proverićemo vaše dokumente uskoro.';
      case 'APPROVED':
        return 'Vaša vozačka dozvola je verifikovana';
      case 'REJECTED':
        return 'Verifikacija odbijena. Kliknite za detalje i ponovni pokušaj.';
      case 'EXPIRED':
        return 'Verifikacija je istekla. Kliknite za obnovu.';
      case 'SUSPENDED':
        return 'Vaš nalog je suspendovan. Kontaktirajte podršku.';
      default:
        return '';
    }
  });

  readonly showSpinner = computed(() => this.status() === 'PENDING_REVIEW');

  readonly routerLink = computed(() => {
    // Suspended users shouldn't be able to navigate
    if (this.status() === 'SUSPENDED') {
      return null;
    }
    return '/verify-license';
  });

  // ============================================================================
  // LIFECYCLE
  // ============================================================================

  ngOnInit(): void {
    // Subscribe to status changes
    this.verificationService.status$.pipe(takeUntil(this.destroy$)).subscribe((profile) => {
      if (profile) {
        this.status.set(profile.status);
        this.loaded.set(true);
      }
    });

    // Load initial status
    this.verificationService.getStatus$().subscribe({
      next: (profile: RenterVerificationProfile | null) => {
        if (profile) {
          this.status.set(profile.status);
          this.loaded.set(true);
        }
      },
      error: (err: unknown) => {
        console.error('Failed to load verification status for badge:', err);
        this.loaded.set(true); // Still show badge even on error
      },
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }
}