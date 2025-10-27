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
  private readonly breakpointObserver = inject(BreakpointObserver);
  private readonly destroyRef = inject(DestroyRef);

  protected isMobile = false;
  protected readonly navigationLinks: NavLink[] = [
    { label: 'Home', icon: 'home', route: '/' },
    { label: 'Cars', icon: 'directions_car', route: '/cars' },
    {
      label: 'Bookings',
      icon: 'assignment',
      route: '/bookings',
      roles: ['USER', 'OWNER'],
    },
    {
      label: 'Reviews',
      icon: 'reviews',
      route: '/reviews',
    },
    {
      label: 'Profile',
      icon: 'person',
      route: '/users/profile',
      roles: ['USER', 'OWNER', 'ADMIN'],
    },
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
