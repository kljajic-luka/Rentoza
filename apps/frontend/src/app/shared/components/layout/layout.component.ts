import { AsyncPipe, CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  OnInit,
  ViewChild,
  inject,
} from '@angular/core';
import { NavigationEnd, Router, RouterModule, RouterOutlet } from '@angular/router';
import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { MatIconModule } from '@angular/material/icon';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import { ToastService } from '@core/services/toast.service';
import { UserProfile } from '@core/models/user.model';
import { UserRole } from '@core/models/user-role.type';
import { ThemeToggleComponent } from '../theme-toggle/theme-toggle.component';
import { ThemeService } from '@core/services/theme.service';

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
    MatIconModule,
    ThemeToggleComponent,
  ],
  templateUrl: './layout.component.html',
  styleUrls: ['./layout.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LayoutComponent implements OnInit {
  protected readonly authService = inject(AuthService);
  protected readonly themeService = inject(ThemeService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly breakpointObserver = inject(BreakpointObserver);
  private readonly destroyRef = inject(DestroyRef);

  // ── Responsive state ──────────────────────────────────────────────────────
  protected isMobile = false;

  // ── Navbar state ──────────────────────────────────────────────────────────
  /** True once user has scrolled >80px — triggers solid navbar */
  protected isScrolled = false;
  /** Controls slide-in mobile drawer */
  protected isMobileMenuOpen = false;
  /** Controls desktop user avatar dropdown */
  protected isUserMenuOpen = false;
  /** Hides entire layout shell for admin routes */
  protected isAdminRoute = false;

  /** Snapshot of authenticated user for sync access (initials, name display) */
  protected currentUserSnapshot: UserProfile | null = null;

  /** Reference to the hamburger toggle — used to restore focus when drawer closes */
  @ViewChild('hamburgerBtn') private hamburgerBtnRef?: ElementRef<HTMLButtonElement>;

  // ── Navigation link definitions ───────────────────────────────────────────

  /** Renter (USER role) navigation links */
  protected readonly renterLinks: NavLink[] = [
    { label: 'Početna', icon: 'home', route: '/pocetna' },
    { label: 'Vozila', icon: 'directions_car', route: '/vozila' },
    { label: 'Rezervacije', icon: 'assignment', route: '/bookings', roles: ['USER', 'ADMIN'] },
    { label: 'Poruke', icon: 'chat', route: '/messages', roles: ['USER', 'ADMIN'] },
    { label: 'Omiljeni', icon: 'favorite', route: '/favorites', roles: ['USER', 'ADMIN'] },
    { label: 'Profil', icon: 'person', route: '/users/profile', roles: ['USER', 'ADMIN'] },
  ];

  /** Owner (OWNER role) navigation links */
  protected readonly ownerLinks: NavLink[] = [
    { label: 'Dashboard', icon: 'dashboard', route: '/owner/dashboard', roles: ['OWNER', 'ADMIN'] },
    { label: 'Moja vozila', icon: 'directions_car', route: '/owner/cars', roles: ['OWNER', 'ADMIN'] },
    { label: 'Rezervacije', icon: 'event', route: '/owner/bookings', roles: ['OWNER', 'ADMIN'] },
    { label: 'Poruke', icon: 'chat', route: '/messages', roles: ['OWNER', 'ADMIN'] },
    { label: 'Zarada', icon: 'account_balance_wallet', route: '/owner/earnings', roles: ['OWNER', 'ADMIN'] },
    { label: 'Recenzije', icon: 'rate_review', route: '/owner/reviews', roles: ['OWNER', 'ADMIN'] },
    { label: 'Verifikacija', icon: 'verified', route: '/owner/verification', roles: ['OWNER', 'ADMIN'] },
    { label: 'Profil', icon: 'person', route: '/users/profile', roles: ['OWNER', 'ADMIN'] },
  ];

  protected readonly currentUser$ = this.authService.currentUser$;
  protected readonly isAuthenticated$ = this.authService.currentUser$.pipe(
    map((user) => user !== null),
  );

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  ngOnInit(): void {
    this.checkAdminRoute();

    // Keep user snapshot in sync for synchronous access (initials, greeting)
    this.currentUser$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((user) => {
        this.currentUserSnapshot = user;
      });

    // Close mobile drawer + user menu on every route navigation
    this.router.events
      .pipe(
        filter((e) => e instanceof NavigationEnd),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.checkAdminRoute();
        this.closeMobileMenu();
        this.isUserMenuOpen = false;
      });

    // Track breakpoint for mobile-specific logic
    this.breakpointObserver
      .observe([Breakpoints.Handset])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        this.isMobile = result.matches;
        // Close mobile drawer when viewport grows past mobile breakpoint
        if (!this.isMobile) {
          this.closeMobileMenu();
        }
      });

    // Handle session expiration gracefully
    this.authService.sessionExpired$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.toast.sessionExpired();
      void this.router.navigate(['/pocetna']);
    });
  }

  // ── Host event listeners ──────────────────────────────────────────────────

  /** Switch navbar to solid-white once user scrolls past 80px */
  @HostListener('window:scroll')
  onWindowScroll(): void {
    this.isScrolled = window.scrollY > 80;
  }

  /**
   * Close user dropdown when clicking anywhere outside it.
   * The toggle button calls stopPropagation so this doesn't immediately
   * close the menu that was just opened.
   */
  @HostListener('document:click')
  onDocumentClick(): void {
    if (this.isUserMenuOpen) {
      this.isUserMenuOpen = false;
    }
  }

  /** Close all menus on Escape key; restore focus to hamburger trigger when drawer was open */
  @HostListener('window:keydown.escape')
  closeOnEscape(): void {
    const drawerWasOpen = this.isMobileMenuOpen;
    this.isMobileMenuOpen = false;
    this.isUserMenuOpen = false;
    this.unlockBodyScroll();
    if (drawerWasOpen) {
      setTimeout(() => this.hamburgerBtnRef?.nativeElement.focus(), 50);
    }
  }

  /**
   * Keyboard accessibility:
   *  - Tab focus trap while mobile drawer is open
   *  - ArrowDown/Up/Home/End navigation in user dropdown
   */
  @HostListener('keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    // ── Focus trap in mobile drawer ──────────────────────────────────────
    if (this.isMobileMenuOpen && event.key === 'Tab') {
      const drawer = document.getElementById('mobile-drawer');
      if (!drawer) return;
      const focusable = Array.from(
        drawer.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ),
      ).filter((el) => !el.hasAttribute('disabled'));
      if (focusable.length < 2) return;
      const first = focusable[0];
      const last  = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
      return;
    }

    // ── Arrow-key navigation in user dropdown ────────────────────────────
    if (this.isUserMenuOpen && ['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      const items = Array.from(
        document.querySelectorAll<HTMLElement>('.user-menu [role="menuitem"]'),
      );
      if (items.length === 0) return;
      event.preventDefault();
      const idx = items.indexOf(document.activeElement as HTMLElement);
      switch (event.key) {
        case 'ArrowDown': items[(idx + 1) % items.length]?.focus(); break;
        case 'ArrowUp':   items[(idx - 1 + items.length) % items.length]?.focus(); break;
        case 'Home':      items[0]?.focus(); break;
        case 'End':       items[items.length - 1]?.focus(); break;
      }
    }
  }

  // ── Navigation helpers ────────────────────────────────────────────────────

  /**
   * Returns navigation links for the top navbar.
   * "Profil" is excluded — it lives in the avatar dropdown instead.
   */
  protected getNavLinks(user: UserProfile | null): NavLink[] {
    return this.getNavigationLinks(user).filter((l) => l.route !== '/users/profile');
  }

  /** Full link set (used in mobile drawer where Profil is surfaced separately) */
  protected getNavigationLinks(user: UserProfile | null): NavLink[] {
    if (!user) return this.renterLinks;

    const isOwner = user.roles?.includes('OWNER') ?? false;
    const isRegularUser = user.roles?.includes('USER') ?? false;

    if (isOwner && !isRegularUser) return this.ownerLinks;
    return this.renterLinks;
  }

  protected canShowLink(link: NavLink, user: UserProfile | null): boolean {
    if (!link.roles?.length) return true;
    if (!user) return false;
    return user.roles?.some((role) => link.roles!.includes(role)) ?? false;
  }

  /** Two-letter initials for the avatar circle */
  protected getUserInitials(): string {
    const u = this.currentUserSnapshot;
    if (!u) return '?';
    const f = u.firstName?.[0] ?? '';
    const l = u.lastName?.[0] ?? '';
    return (f + l).toUpperCase() || (u.email?.[0]?.toUpperCase() ?? '?');
  }

  // ── Menu controls ─────────────────────────────────────────────────────────

  protected toggleMobileMenu(event: MouseEvent): void {
    event.stopPropagation();
    if (this.isMobileMenuOpen) {
      this.closeMobileMenu();
    } else {
      this.isMobileMenuOpen = true;
      this.lockBodyScroll();
    }
  }

  protected closeMobileMenu(restoreFocus = false): void {
    this.isMobileMenuOpen = false;
    this.unlockBodyScroll();
    if (restoreFocus) {
      setTimeout(() => this.hamburgerBtnRef?.nativeElement.focus(), 50);
    }
  }

  protected toggleUserMenu(event: MouseEvent): void {
    // Prevent the document:click listener from immediately closing the menu
    event.stopPropagation();
    this.isUserMenuOpen = !this.isUserMenuOpen;
  }

  protected closeUserMenu(): void {
    this.isUserMenuOpen = false;
  }

  // ── Auth & routing ────────────────────────────────────────────────────────

  protected logout(): void {
    this.closeMobileMenu();
    this.closeUserMenu();
    this.authService.supabaseLogout().subscribe({
      next: () => void this.router.navigate(['/']),
      error: (err) => {
        console.error('Logout error (clearing session anyway):', err);
        void this.router.navigate(['/']);
      },
    });
  }

  protected navigate(route: string): void {
    void this.router.navigate([route]);
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  private checkAdminRoute(): void {
    this.isAdminRoute = this.router.url.startsWith('/admin');
  }

  /** Prevent body scroll while mobile drawer is open (iOS safe) */
  private lockBodyScroll(): void {
    document.body.style.overflow = 'hidden';
  }

  private unlockBodyScroll(): void {
    document.body.style.overflow = '';
  }
}
