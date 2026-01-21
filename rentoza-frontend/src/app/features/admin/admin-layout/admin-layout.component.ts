import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule, NavigationEnd } from '@angular/router';
import { filter, map, Subject, takeUntil, Observable, BehaviorSubject } from 'rxjs';
import { startWith, shareReplay } from 'rxjs/operators';
import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatBadgeModule } from '@angular/material/badge';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AdminStateService } from '../../../core/services/admin-state.service';
import { DashboardKpiDto } from '../../../core/services/admin-api.service';
import { AuthService } from '../../../core/auth/auth.service';
import { ThemeService } from '../../../core/services/theme.service';

@Component({
  selector: 'app-admin-layout',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatToolbarModule,
    MatButtonModule,
    MatBadgeModule,
    MatMenuModule,
    MatDividerModule,
    MatTooltipModule,
  ],
  template: `
    <mat-sidenav-container class="admin-shell safe-area">
      <mat-sidenav 
        #sidenav
        class="admin-sidenav" 
        [mode]="(sidenavMode$ | async) || 'side'" 
        [opened]="(sidenavMode$ | async) === 'side' ? true : (sidenavOpened$ | async)"
        (closedStart)="onSidenavClosed()"
      >
        <div class="brand">
          <div class="brand-mark">RZ</div>
          <div class="brand-copy">
            <div class="brand-title">Rentoza Admin</div>
            <div class="brand-subtitle">Control Center</div>
          </div>
          <button mat-icon-button class="brand-cta" aria-label="Notifications">
            <mat-icon>notifications</mat-icon>
          </button>
        </div>

        <div class="status-card">
          <div class="status-pill">
            <span class="status-dot"></span>
            <div class="status-text">
              <div class="status-label">Systems nominal</div>
              <div class="status-sub">No incidents reported</div>
            </div>
          </div>

          <button mat-stroked-button color="primary" class="status-action">
            <mat-icon>open_in_new</mat-icon>
            Run Checks
          </button>

          <div class="status-metrics">
            <div class="metric-chip">
              <mat-icon class="metric-icon">bolt</mat-icon>
              <div class="metric-info">
                <span class="metric-label">Active Rentals</span>
                <span class="metric-value">{{ (kpis$ | async)?.activeTripsCount ?? '—' }}</span>
              </div>
            </div>
            <div class="metric-chip">
              <mat-icon class="metric-icon">rule_folder</mat-icon>
              <div class="metric-info">
                <span class="metric-label">Approvals</span>
                <span class="metric-value">{{
                  (kpis$ | async)?.pendingApprovalsCount ?? '—'
                }}</span>
              </div>
            </div>
          </div>
        </div>

          <div class="sidenav-scroll">
          <div class="nav-section">
            <div class="nav-label">Overview</div>
            <a
              mat-list-item
              routerLink="/admin/dashboard"
              routerLinkActive="active"
              [routerLinkActiveOptions]="{ exact: true }"
              class="nav-item pill"
            >
              <mat-icon matListItemIcon>dashboard</mat-icon>
              <span matListItemTitle>Dashboard</span>
              <span class="nav-caret">›</span>
            </a>
            <a
              mat-list-item
              routerLink="/admin/users"
              routerLinkActive="active"
              class="nav-item pill"
            >
              <mat-icon matListItemIcon>people</mat-icon>
              <span matListItemTitle>Users</span>
              <span class="nav-caret">›</span>
            </a>
            <a
              mat-list-item
              routerLink="/admin/cars"
              routerLinkActive="active"
              class="nav-item pill"
            >
              <mat-icon matListItemIcon>directions_car</mat-icon>
              <span matListItemTitle>Cars</span>
              <span class="nav-caret">›</span>
            </a>
          </div>

          <div class="nav-section">
            <div class="nav-label">Operations</div>
            <a
              mat-list-item
              routerLink="/admin/renter-verifications"
              routerLinkActive="active"
              class="nav-item pill"
            >
              <mat-icon matListItemIcon>verified_user</mat-icon>
              <span matListItemTitle>Renter Verifications</span>
              <span class="badge" *ngIf="(kpis$ | async)?.pendingApprovalsCount as count">
                {{ count }}
              </span>
              <span class="nav-caret" *ngIf="!(kpis$ | async)?.pendingApprovalsCount">›</span>
            </a>
            <a
              mat-list-item
              routerLink="/admin/disputes"
              routerLinkActive="active"
              class="nav-item pill"
            >
              <mat-icon matListItemIcon>gavel</mat-icon>
              <span matListItemTitle>Disputes</span>
              <span class="badge badge-warning" *ngIf="(kpis$ | async)?.openDisputesCount as count">
                {{ count }}
              </span>
              <span class="nav-caret" *ngIf="!(kpis$ | async)?.openDisputesCount">›</span>
            </a>
            <a
              mat-list-item
              routerLink="/admin/financial"
              routerLinkActive="active"
              class="nav-item pill"
            >
              <mat-icon matListItemIcon>payments</mat-icon>
              <span matListItemTitle>Financial</span>
              <span class="nav-caret">›</span>
            </a>
            <a
              mat-list-item
              routerLink="/admin/settings"
              routerLinkActive="active"
              class="nav-item pill"
            >
              <mat-icon matListItemIcon>settings</mat-icon>
              <span matListItemTitle>Settings</span>
              <span class="nav-caret">›</span>
            </a>
          </div>

          <mat-divider></mat-divider>

          <button mat-stroked-button class="nav-logout" (click)="logout()">
            <mat-icon>logout</mat-icon>
            Logout
          </button>
        </div>
      </mat-sidenav>

      <mat-sidenav-content class="admin-content">
        <mat-toolbar class="admin-toolbar" color="primary">
          <!-- Show hamburger menu button only on mobile -->
          <button 
            *ngIf="(sidenavMode$ | async) === 'over'" 
            mat-icon-button 
            (click)="sidenav.toggle()"
            aria-label="Toggle navigation menu"
            class="sidebar-toggle"
          >
            <mat-icon>menu</mat-icon>
          </button>
          
          <div class="toolbar-left">
            <div class="breadcrumb">
              <span class="crumb-root">Admin Portal</span>
              <mat-icon class="crumb-sep">chevron_right</mat-icon>
              <span class="crumb-leaf">{{ currentRoute }}</span>
            </div>
            <div class="toolbar-title">{{ currentRoute }}</div>
          </div>

          <div class="toolbar-actions">
            <button mat-icon-button aria-label="Alerts" matBadge="3" matBadgeColor="warn">
              <mat-icon>notifications</mat-icon>
            </button>

            <!-- Theme Toggle Button -->
            <button 
              mat-icon-button 
              (click)="toggleTheme()"
              [matTooltip]="themeService.theme() === 'dark' ? 'Switch to Light Mode' : 'Switch to Dark Mode'"
              [attr.aria-label]="themeService.theme() === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'"
            >
              <mat-icon>{{ themeService.theme() === 'dark' ? 'light_mode' : 'dark_mode' }}</mat-icon>
            </button>

            <button mat-icon-button [matMenuTriggerFor]="profileMenu" aria-label="Profile">
              <span class="avatar">A</span>
            </button>
            <mat-menu #profileMenu="matMenu">
              <button mat-menu-item>
                <mat-icon>account_circle</mat-icon>
                <span>Profile</span>
              </button>
              <button mat-menu-item (click)="logout()">
                <mat-icon>logout</mat-icon>
                <span>Logout</span>
              </button>
            </mat-menu>
          </div>
        </mat-toolbar>

        <main class="admin-main">
          <router-outlet></router-outlet>
        </main>
      </mat-sidenav-content>
    </mat-sidenav-container>
  `,
  styleUrls: ['../admin-shared.styles.scss', './admin-layout.component.scss'],
})
export class AdminLayoutComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();
  currentRoute = 'Dashboard';
  kpis$: Observable<DashboardKpiDto | null>;
  
  // Responsive sidebar observables
  sidenavMode$: Observable<'side' | 'over'>;
  sidenavOpened$ = new BehaviorSubject<boolean>(true);

  constructor(
    private router: Router,
    private adminState: AdminStateService,
    private authService: AuthService,
    private breakpointObserver: BreakpointObserver,
    public themeService: ThemeService
  ) {
    this.kpis$ = this.adminState.dashboardKpi$;
    
    // Set up responsive sidebar behavior
    this.sidenavMode$ = this.breakpointObserver
      .observe(Breakpoints.Handset) // < 768px
      .pipe(
        map(result => result.matches ? ('over' as const) : ('side' as const)),
        startWith('side' as 'side' | 'over'),
        shareReplay(1)
      );
    
    this.router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        map((event) => this.mapRouteLabel(event.urlAfterRedirects)),
        takeUntil(this.destroy$)
      )
      .subscribe((label) => (this.currentRoute = label));
  }

  ngOnInit(): void {
    this.adminState.loadDashboardKpis();
  }

  onSidenavClosed(): void {
    this.sidenavMode$.pipe(takeUntil(this.destroy$)).subscribe(mode => {
      if (mode === 'over') {
        this.sidenavOpened$.next(false);
      }
    });
  }

  toggleTheme(): void {
    this.themeService.toggle();
  }

  logout(): void {
    this.authService.supabaseLogout().subscribe({
      next: () => {
        this.router.navigate(['/']);
      },
      error: (err) => {
        console.error('Logout error (clearing session anyway):', err);
        this.router.navigate(['/']);
      },
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private mapRouteLabel(url: string): string {
    const fragment = url.split('?')[0].split('/').filter(Boolean).pop();
    const label = fragment ? fragment.replace('-', ' ') : 'Dashboard';
    return label.charAt(0).toUpperCase() + label.slice(1);
  }
}
