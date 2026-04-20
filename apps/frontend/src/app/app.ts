import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { OverlayContainer } from '@angular/cdk/overlay';
import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { MatDialog } from '@angular/material/dialog';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, NavigationEnd } from '@angular/router';
import { LayoutComponent } from '@shared/components/layout/layout.component';
import { CookieConsentComponent } from '@shared/components/cookie-consent/cookie-consent.component';
import { ToastComponent } from '@shared/components/toast/toast.component';
import { LoadingBarComponent } from '@shared/components/loading-bar/loading-bar.component';
import { filter } from 'rxjs';

type CookieConsentRenderMode = 'fixed' | 'inline';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [LayoutComponent, CookieConsentComponent, ToastComponent, LoadingBarComponent],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly breakpointObserver = inject(BreakpointObserver);
  private readonly dialog = inject(MatDialog);
  private readonly overlayContainer = inject(OverlayContainer);

  private isMobileViewport = false;
  private isAuthRoute = this.isAuthRoutePath(this.router.url);
  private overlayObserver?: MutationObserver;

  /** When true, the router outlet wrapper gets a 'leaving' class to fade out */
  routeLeaving = false;

  readonly cookieConsentRenderMode = signal<CookieConsentRenderMode>('fixed');
  readonly cookieConsentSuspended = signal(false);

  ngOnInit(): void {
    this.startOverlayObserver();

    // Scroll to top on every successful navigation (instant, not smooth –
    // smooth would fight the page transition animation)
    this.router.events
      .pipe(
        filter((e) => e instanceof NavigationEnd),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.isAuthRoute = this.isAuthRoutePath(this.router.url);
        this.updateCookieConsentPresentation();
        window.scrollTo({ top: 0, behavior: 'instant' as ScrollBehavior });
      });

    this.breakpointObserver
      .observe([Breakpoints.Handset])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        this.isMobileViewport = result.matches;
        this.updateCookieConsentPresentation();
      });

    this.dialog.afterOpened.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.updateCookieConsentPresentation();
    });

    this.dialog.afterAllClosed.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.updateCookieConsentPresentation();
    });

    this.updateCookieConsentPresentation();
  }

  private startOverlayObserver(): void {
    if (typeof MutationObserver === 'undefined') {
      return;
    }

    const overlayRoot = this.overlayContainer.getContainerElement();

    this.overlayObserver = new MutationObserver(() => {
      this.updateCookieConsentPresentation();
    });

    this.overlayObserver.observe(overlayRoot, {
      childList: true,
      subtree: true,
    });

    this.destroyRef.onDestroy(() => {
      this.overlayObserver?.disconnect();
    });
  }

  private updateCookieConsentPresentation(): void {
    this.cookieConsentRenderMode.set(this.isMobileViewport && this.isAuthRoute ? 'inline' : 'fixed');
    this.cookieConsentSuspended.set(this.hasBlockingOverlay());
  }

  private hasBlockingOverlay(): boolean {
    const overlayRoot = this.overlayContainer.getContainerElement();

    return Boolean(
      overlayRoot.querySelector('.mat-mdc-dialog-container, .mat-bottom-sheet-container'),
    );
  }

  private isAuthRoutePath(url: string): boolean {
    return /^\/auth(?:\/|$)/.test(url);
  }
}