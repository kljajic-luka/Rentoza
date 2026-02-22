import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AdminRenterVerificationService } from '@core/services/admin-renter-verification.service';
import {
  PagedRenterVerifications,
  RenterVerificationQueueItem,
  DriverLicenseStatus,
  RiskLevel,
  getStatusLabelSr,
  getStatusClass,
  getRiskLevelClass,
} from '@core/models/renter-verification.model';

/**
 * Admin Renter Verification List Component
 *
 * Displays a paginated queue of pending renter driver license verifications.
 *
 * Features:
 * - Filterable by status and risk level
 * - Sortable by date
 * - Shows key info: name, email, risk level, OCR confidence
 * - Click to open detail view
 */
@Component({
  selector: 'app-renter-verification-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatPaginatorModule,
    MatSelectModule,
    MatFormFieldModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatTooltipModule,
  ],
  templateUrl: './renter-verification-list.component.html',
  styleUrls: ['../../admin-shared.styles.scss', './renter-verification-list.component.scss'],
})
export class RenterVerificationListComponent implements OnInit {
  private readonly router = inject(Router);
  private readonly adminService = inject(AdminRenterVerificationService);

  // ============================================================================
  // STATE
  // ============================================================================

  /** Paginated verification data */
  readonly verifications = signal<PagedRenterVerifications | null>(null);

  /** Loading state */
  readonly loading = signal<boolean>(false);

  /** Error state */
  readonly error = signal<string | null>(null);

  /** Current page (0-indexed) */
  readonly currentPage = signal<number>(0);

  /** Page size */
  readonly pageSize = signal<number>(20);

  /** Status filter */
  readonly statusFilter = signal<DriverLicenseStatus | ''>('');

  /** Risk level filter */
  readonly riskFilter = signal<RiskLevel | ''>('');

  /** Sort order */
  readonly sortBy = signal<'newest' | 'oldest' | 'riskLevel'>('newest');

  // ============================================================================
  // TABLE CONFIG
  // ============================================================================

  readonly displayedColumns = ['user', 'riskLevel', 'documentCount', 'submittedAt', 'actions'];

  readonly statusOptions: { value: DriverLicenseStatus | ''; label: string }[] = [
    { value: '', label: 'Svi statusi' },
    { value: 'PENDING_REVIEW', label: 'Na pregledu' },
    { value: 'APPROVED', label: 'Odobreno' },
    { value: 'REJECTED', label: 'Odbijeno' },
    { value: 'EXPIRED', label: 'Isteklo' },
    { value: 'SUSPENDED', label: 'Suspendovano' },
  ];

  readonly riskOptions: { value: RiskLevel | ''; label: string }[] = [
    { value: '', label: 'Svi nivoi' },
    { value: 'LOW', label: 'Nizak' },
    { value: 'MEDIUM', label: 'Srednji' },
    { value: 'HIGH', label: 'Visok' },
  ];

  readonly sortOptions: { value: 'newest' | 'oldest' | 'riskLevel'; label: string }[] = [
    { value: 'newest', label: 'Najnovije prvo' },
    { value: 'oldest', label: 'Najstarije prvo' },
    { value: 'riskLevel', label: 'Po nivou rizika' },
  ];

  // ============================================================================
  // COMPUTED
  // ============================================================================

  /** Total items for paginator */
  readonly totalItems = computed(() => this.verifications()?.totalElements ?? 0);

  /** Is queue empty */
  readonly isEmpty = computed(
    () => !this.loading() && (this.verifications()?.content?.length ?? 0) === 0
  );

  /** Queue items */
  readonly items = computed(() => this.verifications()?.content ?? []);

  // ============================================================================
  // LIFECYCLE
  // ============================================================================

  ngOnInit(): void {
    this.loadVerifications();
  }

  // ============================================================================
  // DATA LOADING
  // ============================================================================

  loadVerifications(): void {
    this.loading.set(true);
    this.error.set(null);

    this.adminService
      .getPendingVerifications({
        page: this.currentPage(),
        size: this.pageSize(),
        status: this.statusFilter() || undefined,
        riskLevel: this.riskFilter() || undefined,
        sortBy: this.sortBy(),
      })
      .subscribe({
        next: (data) => {
          this.verifications.set(data);
          this.loading.set(false);
        },
        error: (err) => {
          this.error.set('Greška pri učitavanju verifikacija');
          this.loading.set(false);
          console.error('Failed to load verifications:', err);
        },
      });
  }

  // ============================================================================
  // EVENT HANDLERS
  // ============================================================================

  onPageChange(event: PageEvent): void {
    this.currentPage.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
    this.loadVerifications();
  }

  onStatusFilterChange(status: DriverLicenseStatus | ''): void {
    this.statusFilter.set(status);
    this.currentPage.set(0);
    this.loadVerifications();
  }

  onRiskFilterChange(risk: RiskLevel | ''): void {
    this.riskFilter.set(risk);
    this.currentPage.set(0);
    this.loadVerifications();
  }

  onSortChange(sort: 'newest' | 'oldest' | 'riskLevel'): void {
    this.sortBy.set(sort);
    this.currentPage.set(0);
    this.loadVerifications();
  }

  onRefresh(): void {
    this.loadVerifications();
  }

  onViewDetail(item: RenterVerificationQueueItem): void {
    this.router.navigate(['/admin/renter-verifications', item.userId]);
  }

  // ============================================================================
  // UTILITY METHODS
  // ============================================================================

  getStatusLabel(status: DriverLicenseStatus): string {
    return getStatusLabelSr(status);
  }

  getStatusClass(status: DriverLicenseStatus): string {
    return getStatusClass(status);
  }

  getRiskClass(level: RiskLevel): string {
    return getRiskLevelClass(level);
  }

  getRiskLabel(level: RiskLevel): string {
    const labels: Record<RiskLevel, string> = {
      LOW: 'Nizak',
      MEDIUM: 'Srednji',
      HIGH: 'Visok',
    };
    return labels[level];
  }

  formatConfidence(confidence: number | null): string {
    if (confidence === null || confidence === undefined) {
      return 'N/A';
    }
    return `${Math.round(confidence * 100)}%`;
  }

  getConfidenceClass(confidence: number | null): string {
    if (confidence === null || confidence === undefined) {
      return 'confidence-unknown';
    }
    if (confidence >= 0.9) return 'confidence-high';
    if (confidence >= 0.7) return 'confidence-medium';
    return 'confidence-low';
  }
}