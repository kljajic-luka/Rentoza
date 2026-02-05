import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

// Angular Material
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { SelectionModel } from '@angular/cdk/collections';

import {
  AdminApiService,
  PayoutQueueDto,
  EscrowBalanceDto,
  BatchPayoutRequest,
} from '../../../../core/services/admin-api.service';

@Component({
  selector: 'app-financial-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatTableModule,
    MatPaginatorModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatCheckboxModule,
    MatSnackBarModule,
    MatTooltipModule,
    MatDividerModule,
  ],
  templateUrl: './financial-dashboard.component.html',
  styleUrls: ['./financial-dashboard.component.scss'],
})
export class FinancialDashboardComponent implements OnInit {
  private adminApi = inject(AdminApiService);
  private snackBar = inject(MatSnackBar);

  // State
  escrowBalance = signal<EscrowBalanceDto | null>(null);
  payouts = signal<PayoutQueueDto[]>([]);
  loading = signal(false);
  processing = signal(false);
  totalElements = signal(0);
  pageSize = signal(20);
  pageIndex = signal(0);

  // Selection
  selection = new SelectionModel<PayoutQueueDto>(true, []);

  // Table
  displayedColumns = [
    'select',
    'bookingId',
    'hostName',
    'amount',
    'scheduledFor',
    'status',
    'actions',
  ];

  // Computed
  hasSelection = computed(() => this.selection.selected.length > 0);
  selectedAmount = computed(() =>
    this.selection.selected.reduce((sum, p) => sum + p.amountCents / 100, 0),
  );

  ngOnInit(): void {
    this.loadEscrowBalance();
    this.loadPayouts();
  }

  loadEscrowBalance(): void {
    this.adminApi.getEscrowBalance().subscribe({
      next: (balance) => this.escrowBalance.set(balance),
      error: (error) => console.error('Failed to load escrow balance:', error),
    });
  }

  loadPayouts(): void {
    this.loading.set(true);
    this.adminApi.getPayoutQueue(this.pageIndex(), this.pageSize()).subscribe({
      next: (response) => {
        this.payouts.set(response.content);
        this.totalElements.set(response.totalElements);
        this.loading.set(false);
        this.selection.clear();
      },
      error: (error) => {
        console.error('Failed to load payouts:', error);
        this.loading.set(false);
      },
    });
  }

  onPageChange(event: PageEvent): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
    this.loadPayouts();
  }

  isAllSelected(): boolean {
    return this.selection.selected.length === this.payouts().length && this.payouts().length > 0;
  }

  toggleAllRows(): void {
    if (this.isAllSelected()) {
      this.selection.clear();
    } else {
      this.payouts().forEach((row) => this.selection.select(row));
    }
  }

  processBatchPayouts(dryRun: boolean = false): void {
    if (this.selection.selected.length === 0) {
      this.snackBar.open('Please select at least one payout', 'Close', { duration: 3000 });
      return;
    }

    const request: BatchPayoutRequest = {
      bookingIds: this.selection.selected.map((p) => p.bookingId),
      dryRun,
      notes: dryRun ? 'Dry run validation' : 'Batch payout processing',
    };

    this.processing.set(true);
    this.adminApi.processBatchPayouts(request).subscribe({
      next: (result) => {
        const message = dryRun
          ? `Validation complete: ${result.successCount} valid, ${result.failureCount} invalid`
          : `Processed: ${result.successCount} success, ${result.failureCount} failed`;

        this.snackBar.open(message, 'Close', { duration: 5000 });
        this.processing.set(false);

        if (!dryRun) {
          this.loadPayouts();
          this.loadEscrowBalance();
        }
      },
      error: (error) => {
        this.snackBar.open('Batch processing failed: ' + error.message, 'Close', {
          duration: 5000,
        });
        this.processing.set(false);
      },
    });
  }

  retryPayout(payout: PayoutQueueDto): void {
    this.adminApi.retryPayout(payout.bookingId).subscribe({
      next: () => {
        this.snackBar.open('Payout retry successful', 'Close', { duration: 3000 });
        this.loadPayouts();
      },
      error: (error) => {
        this.snackBar.open('Retry failed: ' + error.message, 'Close', { duration: 5000 });
      },
    });
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
