import { AsyncPipe, CommonModule, NgClass } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  OnInit,
  ViewChild,
  inject,
} from '@angular/core';
import { Router, RouterModule, RouterOutlet } from '@angular/router';
import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { MatSidenav, MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';
import { ToastrService } from 'ngx-toastr';

import { AuthService } from '@core/auth/auth.service';
import { UserProfile } from '@core/models/user.model';
import { UserRole } from '@core/models/user-role.type';
import { ThemeToggleComponent } from '../theme-toggle/theme-toggle.component';

interface NavLink {
  label: string;
  icon: string;
  route: string;
  roles?: UserRole[];
}

@Component({
  selector: 'app-layout',
  standalone: true,
  imports: [
    CommonModule,
    AsyncPipe,
    RouterModule,
    RouterOutlet,
    MatSidenavModule,
    MatToolbarModule,
    MatIconModule,
    MatButtonModule,
    MatListModule,
    MatMenuModule,
    MatDividerModule,
    MatTooltipModule,
    FlexLayoutModule,
    ThemeToggleComponent,
  ],
  templateUrl: './layout.component.html',
  styleUrls: ['./layout.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LayoutComponent implements OnInit {
  @ViewChild(MatSidenav) sidenav?: MatSidenav;

  protected readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly toastr = inject(ToastrService);
  private readonly breakpointObserver = inject(BreakpointObserver);
  private readonly destroyRef = inject(DestroyRef);

  protected isMobile = false;

  // Renter navigation links (USER role)
  protected readonly renterLinks: NavLink[] = [
    { label: 'Početna', icon: 'home', route: '/pocetna' },
    { label: 'Vozila', icon: 'directions_car', route: '/vozila' },
    { label: 'Rezervacije', icon: 'assignment', route: '/bookings', roles: ['USER', 'ADMIN'] },
    { label: 'Poruke', icon: 'chat', route: '/messages', roles: ['USER', 'ADMIN'] },
    { label: 'Omiljeni', icon: 'favorite', route: '/favorites', roles: ['USER', 'ADMIN'] },
    { label: 'Profil', icon: 'person', route: '/users/profile', roles: ['USER', 'ADMIN'] },
  ];

  // Owner navigation links (OWNER role)
  protected readonly ownerLinks: NavLink[] = [
    { label: 'Dashboard', icon: 'dashboard', route: '/owner/dashboard', roles: ['OWNER', 'ADMIN'] },
    {
      label: 'Moja vozila',
      icon: 'directions_car',
      route: '/owner/cars',
      roles: ['OWNER', 'ADMIN'],
    },
    { label: 'Rezervacije', icon: 'event', route: '/owner/bookings', roles: ['OWNER', 'ADMIN'] },
    { label: 'Poruke', icon: 'chat', route: '/messages', roles: ['OWNER', 'ADMIN'] },
    {
      label: 'Zarada',
      icon: 'account_balance_wallet',
      route: '/owner/earnings',
      roles: ['OWNER', 'ADMIN'],
    },
    { label: 'Recenzije', icon: 'rate_review', route: '/owner/reviews', roles: ['OWNER', 'ADMIN'] },
    {
      label: 'Verifikacija',
      icon: 'verified',
      route: '/owner/verification',
      roles: ['OWNER', 'ADMIN'],
    },
    { label: 'Profil', icon: 'person', route: '/users/profile', roles: ['OWNER', 'ADMIN'] },
  ];

  protected readonly currentUser$ = this.authService.currentUser$;
  protected readonly isAuthenticated$ = this.authService.currentUser$.pipe(
    map((user) => user !== null)
  );

  ngOnInit(): void {
    this.breakpointObserver
      .observe([Breakpoints.Handset])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        this.isMobile = result.matches;
        if (!this.isMobile) {
          this.sidenav?.open();
        }
      });

    this.router.events
      .pipe(
        filter(() => this.isMobile && !!this.sidenav?.opened),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe(() => this.sidenav?.close());

    // Handle session expiration gracefully
    this.authService.sessionExpired$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      console.log('🔔 Session expired - transitioning to guest UI');
      this.toastr.info('Vaša sesija je istekla. Prijavite se ponovo.', 'Sesija istekla', {
        timeOut: 5000,
        progressBar: true,
      });
      void this.router.navigate(['/pocetna']);
    });
  }

  /**
   * Get navigation links based on user role
   */
  protected getNavigationLinks(user: UserProfile | null): NavLink[] {
    if (!user) {
      return this.renterLinks; // Show renter links for guests
    }

    // Check if user is an OWNER (but not a regular USER)
    const isOwner = user.roles?.includes('OWNER') ?? false;
    const isRegularUser = user.roles?.includes('USER') ?? false;

    // If user has OWNER role, show owner navigation
    if (isOwner && !isRegularUser) {
      return this.ownerLinks;
    }

    // Otherwise show renter navigation
    return this.renterLinks;
  }

  protected canShowLink(link: NavLink, user: UserProfile | null): boolean {
    if (!link.roles?.length) {
      return true;
    }

    if (!user) {
      return false;
    }

    return user.roles?.some((role) => link.roles!.includes(role)) ?? false;
  }

  protected logout(): void {
    this.authService.logout();
    this.router.navigate(['/']);
  }

  protected navigate(route: string): void {
    void this.router.navigate([route]);
  }

  @HostListener('window:keydown.escape')
  closeOnEscape(): void {
    if (this.isMobile) {
      this.sidenav?.close();
    }
  }
}
