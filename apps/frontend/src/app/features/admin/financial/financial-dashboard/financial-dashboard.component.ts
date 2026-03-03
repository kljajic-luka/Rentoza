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
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { SelectionModel } from '@angular/cdk/collections';

// Charts
import { BaseChartDirective, provideCharts, withDefaultRegisterables } from 'ng2-charts';
import { ChartConfiguration, ChartOptions } from 'chart.js';

import {
  AdminApiService,
  PayoutQueueDto,
  EscrowBalanceDto,
  BatchPayoutRequest,
} from '../../../../core/services/admin-api.service';
import { AdminNotificationService } from '../../../../core/services/admin-notification.service';
import {
  AdminChartsService,
  PayoutHistoryData,
} from '../../shared/services/admin-charts.service';

@Component({
  selector: 'app-financial-dashboard',
  standalone: true,
  providers: [provideCharts(withDefaultRegisterables())],
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
    MatTooltipModule,
    MatDividerModule,
    BaseChartDirective,
  ],
  templateUrl: './financial-dashboard.component.html',
  styleUrls: ['./financial-dashboard.component.scss'],
})
export class FinancialDashboardComponent implements OnInit {
  private adminApi = inject(AdminApiService);
  private notification = inject(AdminNotificationService);
  private chartsService = inject(AdminChartsService);

  // State
  escrowBalance = signal<EscrowBalanceDto | null>(null);
  payouts = signal<PayoutQueueDto[]>([]);
  loading = signal(false);
  processing = signal(false);
  totalElements = signal(0);
  pageSize = signal(20);
  pageIndex = signal(0);

  // Payout chart
  payoutChartLoading = signal(false);
  payoutChartError = signal<string | null>(null);
  payoutChartData: ChartConfiguration<'line'>['data'] = { labels: [], datasets: [] };
  payoutChartOptions: ChartOptions<'line'> = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: { display: false },
      tooltip: {
        backgroundColor: 'rgba(15, 23, 42, 0.9)',
        padding: 12,
        titleFont: { size: 14, weight: 'bold' },
        bodyFont: { size: 13 },
        cornerRadius: 8,
      },
    },
    scales: {
      y: {
        beginAtZero: true,
        grid: { display: false },
        ticks: { color: '#94a3b8' },
      },
      x: {
        grid: { display: false },
        ticks: { color: '#94a3b8' },
      },
    },
  };

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
    this.loadPayoutHistory();
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

  loadPayoutHistory(): void {
    this.payoutChartLoading.set(true);
    this.payoutChartError.set(null);
    this.chartsService.getPayoutHistory(90).subscribe({
      next: (data: PayoutHistoryData) => {
        this.payoutChartData = {
          labels: data.labels,
          datasets: [
            {
              data: data.amounts,
              label: 'Payouts (RSD)',
              borderColor: '#4caf50',
              backgroundColor: 'rgba(76, 175, 80, 0.1)',
              fill: true,
              tension: 0.3,
              borderWidth: 2,
              pointRadius: 4,
              pointHoverRadius: 6,
              pointBackgroundColor: '#4caf50',
              pointBorderColor: '#fff',
              pointBorderWidth: 2,
            },
          ],
        };
        this.payoutChartLoading.set(false);
      },
      error: () => {
        this.payoutChartError.set('Failed to load payout trend data.');
        this.payoutChartLoading.set(false);
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
      this.notification.showWarning('Please select at least one payout');
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

        this.notification.showSuccess(message);
        this.processing.set(false);

        if (!dryRun) {
          this.loadPayouts();
          this.loadEscrowBalance();
        }
      },
      error: (error) => {
        this.notification.showError('Batch processing failed: ' + error.message);
        this.processing.set(false);
      },
    });
  }

  retryPayout(payout: PayoutQueueDto): void {
    this.adminApi.retryPayout(payout.bookingId).subscribe({
      next: () => {
        this.notification.showSuccess('Payout retry successful');
        this.loadPayouts();
      },
      error: (error) => {
        this.notification.showError('Retry failed: ' + error.message);
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