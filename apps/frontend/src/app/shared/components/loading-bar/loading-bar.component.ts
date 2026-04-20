import {
  Component,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  OnInit,
  OnDestroy,
  inject,
} from '@angular/core';
import { Router, NavigationStart, NavigationEnd, NavigationCancel, NavigationError } from '@angular/router';
import { Subscription } from 'rxjs';

/**
 * Route Loading Bar — Category 4, Interaction 4
 *
 * Thin 3px bar at very top of viewport (fixed, below safe area).
 * Animates from 0→70% on NavigationStart (1s ease-out trickle).
 * Jumps to 100% on NavigationEnd, then fades out (0.3s).
 * Similar to YouTube's red bar or GitHub's blue bar.
 *
 * Uses pure CSS transitions. Zero JS animation libraries.
 */
@Component({
  selector: 'app-loading-bar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="loading-bar"
      [class.loading-bar--active]="loading"
      [class.loading-bar--complete]="complete"
      aria-hidden="true"
    ></div>
  `,
  styles: [`
    .loading-bar {
      position: fixed;
      top: 0;
      left: 0;
      z-index: 10000;
      height: 3px;
      width: 0%;
      background: var(--brand-primary, #593CFB);
      opacity: 0;
      will-change: width, opacity;
      pointer-events: none;
      /* Smooth transition for width changes */
      transition: width 0s, opacity 0.3s ease;
    }

    /* NavigationStart → trickle to 70% */
    .loading-bar--active {
      opacity: 1;
      width: 70%;
      transition:
        width 1s cubic-bezier(0.0, 0.0, 0.2, 1),
        opacity 0.3s ease;
    }

    /* NavigationEnd → jump to 100% then fade */
    .loading-bar--complete {
      opacity: 0;
      width: 100%;
      transition:
        width 0.15s ease-out,
        opacity 0.3s ease 0.15s;
    }

    @media (prefers-reduced-motion: reduce) {
      .loading-bar,
      .loading-bar--active,
      .loading-bar--complete {
        transition: none !important;
      }
    }
  `],
})
export class LoadingBarComponent implements OnInit, OnDestroy {
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);
  private sub?: Subscription;

  loading = false;
  complete = false;

  private resetTimer?: ReturnType<typeof setTimeout>;

  ngOnInit(): void {
    this.sub = this.router.events.subscribe((event) => {
      if (event instanceof NavigationStart) {
        this.start();
      } else if (
        event instanceof NavigationEnd ||
        event instanceof NavigationCancel ||
        event instanceof NavigationError
      ) {
        this.finish();
      }
    });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
    if (this.resetTimer) clearTimeout(this.resetTimer);
  }

  private start(): void {
    if (this.resetTimer) {
      clearTimeout(this.resetTimer);
      this.resetTimer = undefined;
    }
    this.loading = true;
    this.complete = false;
    this.cdr.markForCheck();
  }

  private finish(): void {
    this.loading = false;
    this.complete = true;
    this.cdr.markForCheck();

    // After transition completes, reset to invisible state
    this.resetTimer = setTimeout(() => {
      this.complete = false;
      this.cdr.markForCheck();
    }, 500);
  }
}