import { Component, OnInit, inject, signal, computed } from '@angular/core';
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

    this.adminService.getVerificationDetails(this.userId).subscribe({
      next: (data) => {
        this.details.set(data);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set('Greška pri učitavanju detalja verifikacije');
        this.loading.set(false);
        console.error('Failed to load verification details:', err);
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

    this.adminService.getDocumentSignedUrl(docId).subscribe({
      next: (result) => {
        const urls = new Map(this.documentUrls());
        urls.set(docId, result.url);
        this.documentUrls.set(urls);
        this.loadingDocId.set(null);
      },
      error: (err) => {
        this.notification.showError('Greška pri učitavanju dokumenta');
        this.loadingDocId.set(null);
        console.error('Failed to load document URL:', err);
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

    if (!confirm('Da li ste sigurni da želite da odobrite ovu verifikaciju?')) {
      return;
    }

    this.actionLoading.set(true);

    this.adminService.approve(this.userId, { notes: 'Approved via admin panel' }).subscribe({
      next: () => {
        this.notification.showSuccess('Verifikacija je odobrena');
        this.loadDetails();
        this.actionLoading.set(false);
      },
      error: (err) => {
        this.notification.showError('Greška pri odobravanju verifikacije');
        this.actionLoading.set(false);
        console.error('Failed to approve:', err);
      },
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
    const { RejectRenterVerificationDialogComponent } = await import(
      './reject-renter-verification-dialog.component'
    );

    const dialogRef = this.dialog.open(RejectRenterVerificationDialogComponent, {
      width: '520px',
      data: { displayName },
    });

    dialogRef.afterClosed().subscribe((reason) => {
      if (reason && this.userId) {
        this.executeReject(reason);
      }
    });
  }

  private executeReject(reason: string): void {
    if (!this.userId) return;

    this.actionLoading.set(true);

    this.adminService.reject(this.userId, { reason }).subscribe({
      next: () => {
        this.notification.showSuccess('Verifikacija je odbijena');
        this.loadDetails();
        this.actionLoading.set(false);
      },
      error: (err) => {
        this.notification.showError('Greška pri odbijanju verifikacije');
        this.actionLoading.set(false);
        console.error('Failed to reject:', err);
      },
    });
  }

  /**
   * Suspend the user.
   */
  onSuspend(): void {
    if (!this.userId || !this.canSuspend()) return;

    const reason = prompt('Unesite razlog suspenzije:');
    if (!reason) return;

    this.actionLoading.set(true);

    this.adminService.suspend(this.userId, { reason }).subscribe({
      next: () => {
        this.notification.showSuccess('Korisnik je suspendovan');
        this.loadDetails();
        this.actionLoading.set(false);
      },
      error: (err) => {
        this.notification.showError('Greška pri suspenziji korisnika');
        this.actionLoading.set(false);
        console.error('Failed to suspend:', err);
      },
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
    this.notification.showError('Greška pri učitavanju slike');
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