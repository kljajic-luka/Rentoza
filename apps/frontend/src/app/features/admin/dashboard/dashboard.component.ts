import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MatGridListModule } from '@angular/material/grid-list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { KpiCardComponent } from '../shared/components/kpi-card/kpi-card.component';
import { DashboardChartsComponent } from './components/dashboard-charts/dashboard-charts.component';
import { AdminStateService } from '../../../core/services/admin-state.service';
import { AdminApiService, DashboardKpiDto } from '../../../core/services/admin-api.service';
import { ExportService } from '../../../core/services/export.service';
import { AdminNotificationService } from '../../../core/services/admin-notification.service';
import { Observable, take } from 'rxjs';

@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    MatGridListModule,
    MatIconModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    MatTableModule,
    MatChipsModule,
    KpiCardComponent,
    DashboardChartsComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrls: ['../admin-shared.styles.scss', './dashboard.component.scss'],
})
export class AdminDashboardComponent implements OnInit {
  private adminState = inject(AdminStateService);
  private adminApi = inject(AdminApiService);
  private exportService = inject(ExportService);
  private notification = inject(AdminNotificationService);

  kpis$: Observable<DashboardKpiDto | null>;
  loading$: Observable<boolean>;
  error$: Observable<string | null>;

  // Recent bookings state
  recentBookings: any[] = [];
  recentBookingsLoading = false;

  constructor() {
    this.kpis$ = this.adminState.dashboardKpi$;
    this.loading$ = this.adminState.loading$;
    this.error$ = this.adminState.error$;
  }

  ngOnInit(): void {
    this.adminState.loadDashboardKpis();
    this.loadRecentBookings();
  }

  refresh(): void {
    this.adminState.loadDashboardKpis();
    this.loadRecentBookings();
  }

  loadRecentBookings(): void {
    this.recentBookingsLoading = true;
    // Load 5 most recent bookings for the dashboard
    this.adminApi.getRecentBookings(5).subscribe({
      next: (bookings) => {
        this.recentBookings = bookings;
        this.recentBookingsLoading = false;
      },
      error: () => {
        this.recentBookingsLoading = false;
        // Silent fail - this is optional data
      },
    });
  }

  exportSnapshot(): void {
    this.kpis$.pipe(take(1)).subscribe((kpis) => {
      if (!kpis) {
        this.notification.showInfo('No data to export');
        return;
      }

      const snapshot = {
        'Report Date': new Date().toISOString(),
        'Total Revenue This Month': `RSD ${kpis.totalRevenueThisMonth?.toLocaleString() || 0}`,
        'Revenue Growth': `${kpis.revenueGrowthPercent}%`,
        'Active Trips': kpis.activeTripsCount,
        'Pending Approvals': kpis.pendingApprovalsCount,
        'Open Disputes': kpis.openDisputesCount,
        'Suspended Users': kpis.suspendedUsersCount,
        'Platform Health': `${kpis.platformHealthScore}%`,
        'Last Calculated': kpis.calculatedAt,
      };

      this.exportService.exportToCsv(
        [snapshot],
        `rentoza_dashboard_snapshot_${new Date().toISOString().split('T')[0]}`,
      );
      this.notification.showSuccess('Dashboard snapshot exported');
    });
  }

  getStatusColor(status: string): string {
    switch (status) {
      case 'CONFIRMED':
        return 'primary';
      case 'COMPLETED':
        return 'accent';
      case 'IN_PROGRESS':
        return 'primary';
      case 'CANCELLED':
        return 'warn';
      default:
        return '';
    }
  }
}