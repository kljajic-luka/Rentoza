import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

// Angular Material
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AdminApiService, AdminDisputeListDto } from '../../../../core/services/admin-api.service';

/**
 * Dispute List Component - Admin view of all damage claim disputes.
 *
 * Features:
 * - Paginated table with status filtering
 * - Status chips with color coding
 * - Click row to view details
 * - Real-time dispute count by status
 * - Responsive layout
 */
@Component({
  selector: 'app-dispute-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatTableModule,
    MatPaginatorModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatFormFieldModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatTooltipModule,
  ],
  templateUrl: './dispute-list.component.html',
  styleUrls: ['./dispute-list.component.scss'],
})
export class DisputeListComponent implements OnInit {
  private adminApi = inject(AdminApiService);
  private router = inject(Router);

  // State
  disputes = signal<AdminDisputeListDto[]>([]);
  loading = signal(false);
  totalElements = signal(0);
  pageSize = signal(20);
  pageIndex = signal(0);
  selectedStatus = signal<string>('ALL');

  // Table configuration
  displayedColumns = [
    'id',
    'status',
    'guestName',
    'hostName',
    'estimatedCost',
    'createdAt',
    'actions',
  ];

  // Status options
  statusOptions = [
    { value: 'ALL', label: 'All Disputes' },
    { value: 'DISPUTED', label: 'Disputed' },
    { value: 'ESCALATED', label: 'Escalated' },
    { value: 'ADMIN_APPROVED', label: 'Approved' },
    { value: 'ADMIN_REJECTED', label: 'Rejected' },
    { value: 'REQUIRES_MANUAL_REVIEW', label: 'Manual Review' },
    { value: 'CHECKOUT_PENDING', label: 'Checkout - Pending' },
    { value: 'CHECKOUT_GUEST_DISPUTED', label: 'Checkout - Guest Disputed' },
    { value: 'CHECKOUT_TIMEOUT_ESCALATED', label: 'Checkout - Timeout Escalated' },
    { value: 'CHECKOUT_ADMIN_APPROVED', label: 'Checkout - Approved' },
    { value: 'CHECKOUT_ADMIN_REJECTED', label: 'Checkout - Rejected' },
  ];

  // Computed
  hasDisputes = computed(() => this.disputes().length > 0);
  isFiltering = computed(() => this.selectedStatus() !== 'ALL');

  ngOnInit(): void {
    this.loadDisputes();
  }

  loadDisputes(): void {
    this.loading.set(true);
    const status = this.selectedStatus() === 'ALL' ? undefined : this.selectedStatus();

    this.adminApi.listDisputes(this.pageIndex(), this.pageSize(), status).subscribe({
      next: (response) => {
        this.disputes.set(response.content);
        this.totalElements.set(response.totalElements);
        this.loading.set(false);
      },
      error: (error) => {
        console.error('Failed to load disputes:', error);
        this.loading.set(false);
      },
    });
  }

  onStatusChange(): void {
    this.pageIndex.set(0);
    this.loadDisputes();
  }

  onPageChange(event: PageEvent): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
    this.loadDisputes();
  }

  viewDisputeDetail(dispute: AdminDisputeListDto): void {
    this.router.navigate(['/admin/disputes', dispute.id]);
  }

  getStatusColor(status: string): string {
    const colorMap: Record<string, string> = {
      DISPUTED: 'warn',
      ESCALATED: 'accent',
      ADMIN_APPROVED: 'primary',
      ADMIN_REJECTED: '',
      REQUIRES_MANUAL_REVIEW: 'warn',
      PAID: 'primary',
      CHECKOUT_PENDING: 'accent',
      CHECKOUT_GUEST_ACCEPTED: 'primary',
      CHECKOUT_GUEST_DISPUTED: 'warn',
      CHECKOUT_ADMIN_APPROVED: 'primary',
      CHECKOUT_ADMIN_REJECTED: '',
      CHECKOUT_TIMEOUT_ESCALATED: 'warn',
    };
    return colorMap[status] || '';
  }

  getStatusLabel(status: string): string {
    const labelMap: Record<string, string> = {
      DISPUTED: 'Disputed',
      ESCALATED: 'Escalated',
      ADMIN_APPROVED: 'Approved',
      ADMIN_REJECTED: 'Rejected',
      REQUIRES_MANUAL_REVIEW: 'Manual Review',
      PAID: 'Paid',
      CHECKOUT_PENDING: 'Checkout Pending',
      CHECKOUT_GUEST_ACCEPTED: 'Guest Accepted',
      CHECKOUT_GUEST_DISPUTED: 'Guest Disputed',
      CHECKOUT_ADMIN_APPROVED: 'Checkout Approved',
      CHECKOUT_ADMIN_REJECTED: 'Checkout Rejected',
      CHECKOUT_TIMEOUT_ESCALATED: 'Timeout Escalated',
    };
    return labelMap[status] || status;
  }

  formatCurrency(cents: number): string {
    const amount = (cents / 100).toLocaleString('sr-RS', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 0,
    });
    return `${amount} RSD`;
  }

  formatDate(dateString: string): string {
    return new Date(dateString).toLocaleDateString('sr-RS', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  }
}