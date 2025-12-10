import { Component, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BaseChartDirective } from 'ng2-charts';
import { ChartConfiguration, ChartOptions } from 'chart.js';
import { MatCardModule } from '@angular/material/card';
import { MatTabsModule } from '@angular/material/tabs';

@Component({
  selector: 'app-dashboard-charts',
  standalone: true,
  imports: [CommonModule, BaseChartDirective, MatCardModule, MatTabsModule],
  template: `
    <div class="charts-grid">
      <mat-card class="surface-card surface-wide chart-card">
        <mat-card-header>
          <mat-card-title>Revenue Trends</mat-card-title>
          <mat-card-subtitle>Last 6 months</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <canvas
            baseChart
            [data]="revenueChartData"
            [options]="revenueChartOptions"
            [type]="'line'"
          >
          </canvas>
        </mat-card-content>
      </mat-card>

      <mat-card class="surface-card surface-wide chart-card">
        <mat-card-header>
          <mat-card-title>Trip Activity</mat-card-title>
          <mat-card-subtitle>Weekly breakdown</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <canvas baseChart [data]="tripsChartData" [options]="tripsChartOptions" [type]="'bar'">
          </canvas>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styleUrls: ['../../../admin-shared.styles.scss', './dashboard-charts.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardChartsComponent {
  // Mock Data for MVP - will be replaced by API service in future

  public revenueChartData: ChartConfiguration<'line'>['data'] = {
    labels: ['Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'],
    datasets: [
      {
        data: [12000, 15000, 14500, 18000, 21000, 24500],
        label: 'Revenue (€)',
        fill: true,
        tension: 0.4,
        borderColor: '#3b82f6', // blue-500
        backgroundColor: 'rgba(59, 130, 246, 0.1)',
      },
    ],
  };

  public revenueChartOptions: ChartOptions<'line'> = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: { display: false },
    },
    scales: {
      y: {
        beginAtZero: true,
        grid: { display: false },
      },
      x: {
        grid: { display: false },
      },
    },
  };

  public tripsChartData: ChartConfiguration<'bar'>['data'] = {
    labels: ['Nov W1', 'Nov W2', 'Nov W3', 'Nov W4', 'Dec W1', 'Dec W2'],
    datasets: [
      {
        data: [45, 52, 48, 60, 65, 72],
        label: 'Completed Trips',
        backgroundColor: '#10b981', // green-500
        borderRadius: 4,
      },
    ],
  };

  public tripsChartOptions: ChartOptions<'bar'> = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: { display: false },
    },
    scales: {
      y: {
        beginAtZero: true,
        grid: { display: false },
      },
      x: {
        grid: { display: false },
      },
    },
  };
}
