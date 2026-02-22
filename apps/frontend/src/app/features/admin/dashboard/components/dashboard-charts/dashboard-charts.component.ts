import { Component, ChangeDetectionStrategy, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BaseChartDirective, provideCharts, withDefaultRegisterables } from 'ng2-charts';
import { ChartConfiguration, ChartOptions } from 'chart.js';
import { MatCardModule } from '@angular/material/card';
import { MatTabsModule } from '@angular/material/tabs';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import {
  AdminChartsService,
  RevenueChartData,
  TripActivityData,
} from '../../../shared/services/admin-charts.service';
import { BehaviorSubject, Subject, takeUntil } from 'rxjs';

@Component({
  selector: 'app-dashboard-charts',
  standalone: true,
  // provideCharts scoped here so chart.js is NOT pulled into the initial bundle.
  // Only loads when the admin/dashboard lazy chunk is activated.
  providers: [provideCharts(withDefaultRegisterables())],
  imports: [
    CommonModule,
    BaseChartDirective,
    MatCardModule,
    MatTabsModule,
    MatProgressSpinnerModule,
    MatButtonModule,
    MatIconModule,
  ],
  template: `
    <div class="charts-grid">
      <!-- Revenue Chart -->
      <mat-card class="surface-card surface-wide chart-card">
        <mat-card-header>
          <div class="chart-header">
            <div>
              <mat-card-title>Revenue Trends</mat-card-title>
              <mat-card-subtitle>Last {{ period }} months</mat-card-subtitle>
            </div>
            <div class="chart-controls">
              <button
                mat-icon-button
                (click)="changePeriod(3)"
                [class.active]="period === 3"
                aria-label="Show 3 months"
              >
                3M
              </button>
              <button
                mat-icon-button
                (click)="changePeriod(6)"
                [class.active]="period === 6"
                aria-label="Show 6 months"
              >
                6M
              </button>
              <button
                mat-icon-button
                (click)="changePeriod(12)"
                [class.active]="period === 12"
                aria-label="Show 12 months"
              >
                1Y
              </button>
            </div>
          </div>
        </mat-card-header>
        <mat-card-content>
          <div *ngIf="revenueLoading$ | async" class="chart-loading">
            <mat-progress-spinner mode="indeterminate" diameter="40"></mat-progress-spinner>
            <span class="loading-text">Loading revenue data...</span>
          </div>
          <div *ngIf="revenueError$ | async as error" class="chart-error" role="alert">
            <mat-icon>error_outline</mat-icon>
            <span>{{ error }}</span>
          </div>
          <canvas
            *ngIf="!(revenueLoading$ | async) && !(revenueError$ | async)"
            baseChart
            [data]="revenueChartData"
            [options]="revenueChartOptions"
            [type]="'line'"
          >
          </canvas>
        </mat-card-content>
      </mat-card>

      <!-- Trip Activity Chart -->
      <mat-card class="surface-card surface-wide chart-card">
        <mat-card-header>
          <div class="chart-header">
            <div>
              <mat-card-title>Trip Activity</mat-card-title>
              <mat-card-subtitle>Weekly breakdown</mat-card-subtitle>
            </div>
          </div>
        </mat-card-header>
        <mat-card-content>
          <div *ngIf="tripsLoading$ | async" class="chart-loading">
            <mat-progress-spinner mode="indeterminate" diameter="40"></mat-progress-spinner>
            <span class="loading-text">Loading trip data...</span>
          </div>
          <div *ngIf="tripsError$ | async as error" class="chart-error" role="alert">
            <mat-icon>error_outline</mat-icon>
            <span>{{ error }}</span>
          </div>
          <canvas
            *ngIf="!(tripsLoading$ | async) && !(tripsError$ | async)"
            baseChart
            [data]="tripsChartData"
            [options]="tripsChartOptions"
            [type]="'bar'"
          >
          </canvas>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styleUrls: ['../../../admin-shared.styles.scss', './dashboard-charts.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardChartsComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  // Loading and error states
  revenueLoading$ = new BehaviorSubject<boolean>(false);
  tripsLoading$ = new BehaviorSubject<boolean>(false);
  revenueError$ = new BehaviorSubject<string | null>(null);
  tripsError$ = new BehaviorSubject<string | null>(null);

  // Chart data
  revenueChartData: ChartConfiguration<'line'>['data'] = { labels: [], datasets: [] };
  tripsChartData: ChartConfiguration<'bar'>['data'] = { labels: [], datasets: [] };

  // Period for revenue chart (in months)
  period = 6;

  // Chart options
  public revenueChartOptions: ChartOptions<'line'> = {
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

  public tripsChartOptions: ChartOptions<'bar'> = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        display: true,
        position: 'bottom',
        labels: {
          color: '#94a3b8',
          padding: 16,
          font: { size: 12 },
        },
      },
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

  constructor(private chartsService: AdminChartsService) {}

  ngOnInit(): void {
    this.loadRevenueChart();
    this.loadTripActivity();
  }

  /**
   * Loads revenue chart data from the service
   */
  loadRevenueChart(): void {
    this.revenueLoading$.next(true);
    this.revenueError$.next(null);

    this.chartsService
      .getRevenueChart(this.period)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data: RevenueChartData) => {
          this.revenueChartData = {
            labels: data.labels,
            datasets: [
              {
                data: data.totalRevenue,
                label: `Revenue (${data.currencyCode})`,
                fill: true,
                tension: 0.4,
                borderColor: '#3b82f6', // blue-500
                backgroundColor: 'rgba(59, 130, 246, 0.1)',
                borderWidth: 2,
                pointRadius: 4,
                pointHoverRadius: 6,
                pointBackgroundColor: '#3b82f6',
                pointBorderColor: '#fff',
                pointBorderWidth: 2,
              },
            ],
          };
          this.revenueLoading$.next(false);
        },
        error: (error: unknown) => {
          console.error('Failed to load revenue chart:', error);
          this.revenueError$.next('Failed to load revenue data. Please try again later.');
          this.revenueLoading$.next(false);
        },
      });
  }

  /**
   * Loads trip activity chart data from the service
   */
  loadTripActivity(): void {
    this.tripsLoading$.next(true);
    this.tripsError$.next(null);

    this.chartsService
      .getTripActivity(6)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data: TripActivityData) => {
          this.tripsChartData = {
            labels: data.labels,
            datasets: [
              {
                data: data.completedTrips,
                label: 'Completed Trips',
                backgroundColor: '#10b981', // green-500
                borderRadius: 4,
                borderSkipped: false,
              },
              {
                data: data.canceledTrips,
                label: 'Canceled Trips',
                backgroundColor: 'rgba(239, 68, 68, 0.6)', // red-500 with opacity
                borderRadius: 4,
                borderSkipped: false,
              },
            ],
          };
          this.tripsLoading$.next(false);
        },
        error: (error: unknown) => {
          console.error('Failed to load trip activity chart:', error);
          this.tripsError$.next('Failed to load trip data. Please try again later.');
          this.tripsLoading$.next(false);
        },
      });
  }

  /**
   * Changes the revenue chart period and reloads data
   */
  changePeriod(newPeriod: number): void {
    if (this.period !== newPeriod) {
      this.period = newPeriod;
      this.loadRevenueChart();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }
}
