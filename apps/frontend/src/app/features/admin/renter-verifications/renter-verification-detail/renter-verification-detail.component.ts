import { Component, OnInit, inject, signal, computed, DestroyRef } from '@angular/core';
import { CommonModule, Location } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';

import { AdminRenterVerificationService } from '@core/services/admin-renter-verification.service';
import { AdminNotificationService } from '@core/services/admin-notification.service';
import { ConfirmDialogComponent } from '../../shared/dialogs/confirm-dialog/confirm-dialog.component';
import {
  AdminVerificationDetails,
  VerificationAuditEvent,
  DriverLicenseStatus,
  RiskLevel,
  getStatusLabelSr,
  getStatusClass,
  getStatusIcon,
  getRiskLevelClass,
} from '@core/models/renter-verification.model';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';

/**
 * Admin Renter Verification Detail Component
 *
 * Detailed view for reviewing a single renter's driver license verification.
 *
 * Features:
 * - View uploaded documents via signed URLs
 * - Review OCR extraction results
 * - Approve, reject, or suspend verification
 * - View audit trail of actions
 */
@Component({
  selector: 'app-renter-verification-detail',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatTabsModule,
    MatChipsModule,
    MatDialogModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatDividerModule,
  ],
  templateUrl: './renter-verification-detail.component.html',
  styleUrls: ['../../admin-shared.styles.scss', './renter-verification-detail.component.scss'],
})
export class RenterVerificationDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly dialog = inject(MatDialog);
  private readonly adminService = inject(AdminRenterVerificationService);
  private readonly notification = inject(AdminNotificationService);
  private readonly destroyRef = inject(DestroyRef);

  // ============================================================================
  // STATE
  // ============================================================================

  /** User ID being reviewed */
  userId: number | null = null;

  /** Verification details */
  readonly details = signal<AdminVerificationDetails | null>(null);

  /** Loading state */
  readonly loading = signal<boolean>(false);

  /** Error state */
  readonly error = signal<string | null>(null);

  /** Action in progress (approve/reject/suspend) */
  readonly actionLoading = signal<boolean>(false);

  /** Document signed URLs (keyed by document ID) */
  readonly documentUrls = signal<Map<number, string>>(new Map());

  /** Currently loading document ID */
  readonly loadingDocId = signal<number | null>(null);

  // ============================================================================
  // COMPUTED
  // ============================================================================

  /** Current status */
  readonly status = computed(() => this.details()?.status ?? 'NOT_STARTED');

  /** Status label */
  readonly statusLabel = computed(() => getStatusLabelSr(this.status()));

  /** Status CSS class */
  readonly statusClass = computed(() => getStatusClass(this.status()));

  /** Status icon */
  readonly statusIcon = computed(() => getStatusIcon(this.status()));

  /** Can approve (only if pending) */
  readonly canApprove = computed(() => this.status() === 'PENDING_REVIEW');

  /** Can reject (only if pending) */
  readonly canReject = computed(() => this.status() === 'PENDING_REVIEW');

  /** Can suspend */
  readonly canSuspend = computed(() => !['SUSPENDED', 'NOT_STARTED'].includes(this.status()));

  /** OCR results */
  readonly ocrResults = computed(() => this.details()?.ocrResults ?? null);

  /** Audit trail */
  readonly auditTrail = computed(() => this.details()?.auditTrail ?? []);

  /** Documents */
  readonly documents = computed(() => this.details()?.documents ?? []);

  // ============================================================================
  // LIFECYCLE
  // ============================================================================

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('userId');
    if (id) {
      this.userId = +id;
      this.loadDetails();
    } else {
      this.goBack();
    }
  }

  // ============================================================================
  // DATA LOADING
  // ============================================================================

  loadDetails(): void {
    if (!this.userId) return;

    this.loading.set(true);
    this.error.set(null);

    this.adminService
      .getVerificationDetails(this.userId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (data) => {
          this.details.set(data);
        },
        error: () => {
          this.error.set('Failed to load verification details');
        },
      });
  }

  /**
   * Load signed URL for a document.
   */
  loadDocumentUrl(docId: number): void {
    // Check if already loaded
    if (this.documentUrls().has(docId)) {
      return;
    }

    this.loadingDocId.set(docId);

    this.adminService
      .getDocumentSignedUrl(docId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loadingDocId.set(null)),
      )
      .subscribe({
        next: (result) => {
          const urls = new Map(this.documentUrls());
          urls.set(docId, result.url);
          this.documentUrls.set(urls);
        },
        error: () => {
          this.notification.showError('Failed to load document');
        },
      });
  }

  // ============================================================================
  // ACTIONS
  // ============================================================================

  /**
   * Approve the verification.
   */
  onApprove(): void {
    if (!this.userId || !this.canApprove()) return;

    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: 'Approve Verification',
        message: 'Are you sure you want to approve this verification?',
        confirmText: 'Approve',
        confirmColor: 'primary',
      },
    });
    dialogRef
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        if (!result) return;

        this.actionLoading.set(true);

        this.adminService
          .approve(this.userId!, { notes: 'Approved via admin panel' })
          .pipe(
            takeUntilDestroyed(this.destroyRef),
            finalize(() => this.actionLoading.set(false)),
          )
          .subscribe({
            next: () => {
              this.notification.showSuccess('Verification approved');
              this.loadDetails();
            },
            error: () => {
              this.notification.showError('Failed to approve verification');
            },
          });
      });
  }

  /**
   * Open reject dialog.
   */
  async onReject(): Promise<void> {
    if (!this.userId || !this.canReject()) return;

    const details = this.details();
    const displayName = details?.fullName ?? 'ovog korisnika';

    // Dynamic import to avoid circular dependency
    const { RejectRenterVerificationDialogComponent } =
      await import('./reject-renter-verification-dialog.component');

    const dialogRef = this.dialog.open(RejectRenterVerificationDialogComponent, {
      width: '520px',
      data: { displayName },
    });

    dialogRef
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((reason) => {
        if (reason && this.userId) {
          this.executeReject(reason);
        }
      });
  }

  private executeReject(reason: string): void {
    if (!this.userId) return;

    this.actionLoading.set(true);

    this.adminService
      .reject(this.userId, { reason })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.actionLoading.set(false)),
      )
      .subscribe({
        next: () => {
          this.notification.showSuccess('Verification rejected');
          this.loadDetails();
        },
        error: () => {
          this.notification.showError('Failed to reject verification');
        },
      });
  }

  /**
   * Suspend the user.
   */
  onSuspend(): void {
    if (!this.userId || !this.canSuspend()) return;

    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: 'Suspend User',
        message: 'Enter the reason for suspending this user.',
        confirmText: 'Suspend',
        confirmColor: 'warn',
        requireReason: true,
        reasonLabel: 'Suspension reason',
      },
    });
    dialogRef
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((reason) => {
        if (!reason) return;

        this.actionLoading.set(true);

        this.adminService
          .suspend(this.userId!, { reason })
          .pipe(
            takeUntilDestroyed(this.destroyRef),
            finalize(() => this.actionLoading.set(false)),
          )
          .subscribe({
            next: () => {
              this.notification.showSuccess('User suspended');
              this.loadDetails();
            },
            error: () => {
              this.notification.showError('Failed to suspend user');
            },
          });
      });
  }

  // ============================================================================
  // NAVIGATION
  // ============================================================================

  goBack(): void {
    this.location.back();
  }

  // ============================================================================
  // UTILITY
  // ============================================================================

  onImageError(): void {
    this.notification.showError('Failed to load image');
  }

  getRiskLabel(level: RiskLevel | null): string {
    if (!level) return 'N/A';
    const labels: Record<RiskLevel, string> = {
      LOW: 'Nizak',
      MEDIUM: 'Srednji',
      HIGH: 'Visok',
    };
    return labels[level];
  }

  getRiskClass(level: RiskLevel | null): string {
    if (!level) return '';
    return getRiskLevelClass(level);
  }

  formatPercent(value: number | null | undefined): string {
    if (value === null || value === undefined) return 'N/A';
    return `${Math.round(value * 100)}%`;
  }

  formatDate(dateStr: string | null): string {
    if (!dateStr) return 'N/A';
    return new Date(dateStr).toLocaleString('sr-RS');
  }

  getAuditActionLabel(action: string): string {
    const labels: Record<string, string> = {
      DOCUMENT_SUBMITTED: 'Dokument podnet',
      AUTO_APPROVED: 'Auto-odobreno',
      AUTO_REJECTED: 'Auto-odbijeno',
      MANUAL_APPROVED: 'Ručno odobreno',
      MANUAL_REJECTED: 'Ručno odbijeno',
      SUSPENDED: 'Suspendovan',
      RESUBMISSION_REQUESTED: 'Zatražena ponovna prijava',
      EXPIRED: 'Isteklo',
      REACTIVATED: 'Reaktivirano',
    };
    return labels[action] || action;
  }

  getDocumentTypeLabel(type: string): string {
    const labels: Record<string, string> = {
      DRIVERS_LICENSE_FRONT: 'Vozačka dozvola - prednja strana',
      DRIVERS_LICENSE_BACK: 'Vozačka dozvola - zadnja strana',
      SELFIE: 'Selfie fotografija',
      ID_CARD_FRONT: 'Lična karta - prednja strana',
      ID_CARD_BACK: 'Lična karta - zadnja strana',
      PASSPORT: 'Pasoš',
    };
    return labels[type] || type;
  }
}
