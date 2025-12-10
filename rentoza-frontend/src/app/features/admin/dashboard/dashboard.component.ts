import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatGridListModule } from '@angular/material/grid-list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { KpiCardComponent } from '../shared/components/kpi-card/kpi-card.component';
import { DashboardChartsComponent } from './components/dashboard-charts/dashboard-charts.component';
import { AdminStateService } from '../../../core/services/admin-state.service';
import { Observable } from 'rxjs';
import { DashboardKpiDto } from '../../../core/services/admin-api.service';

@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    MatGridListModule,
    MatIconModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    KpiCardComponent,
    DashboardChartsComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrls: ['../admin-shared.styles.scss', './dashboard.component.scss'],
})
export class AdminDashboardComponent implements OnInit {
  kpis$: Observable<DashboardKpiDto | null>;
  loading$: Observable<boolean>;
  error$: Observable<string | null>;

  constructor(private adminState: AdminStateService) {
    this.kpis$ = this.adminState.dashboardKpi$;
    this.loading$ = this.adminState.loading$;
    this.error$ = this.adminState.error$;
  }

  ngOnInit(): void {
    this.adminState.loadDashboardKpis();
  }

  refresh(): void {
    this.adminState.loadDashboardKpis();
  }
}
