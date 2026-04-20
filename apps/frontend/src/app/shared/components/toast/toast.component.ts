import {
  Component,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  OnInit,
  OnDestroy,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { ToastNotificationService } from '@core/services/toast-notification.service';
import type { Toast } from '@core/services/toast-notification.service';

@Component({
  selector: 'app-toast',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toast-container" role="status" aria-live="polite" aria-atomic="false">
      @for (toast of toasts; track toast.id) {
        <div
          class="toast"
          [class.toast--success]="toast.type === 'success'"
          [class.toast--error]="toast.type === 'error'"
          [class.toast--warning]="toast.type === 'warning'"
          [class.toast--info]="toast.type === 'info'"
          [class.toast--entering]="toast.state === 'entering'"
          [class.toast--visible]="toast.state === 'visible'"
          [class.toast--exiting]="toast.state === 'exiting'"
          (click)="dismiss(toast)"
          role="alert"
          [attr.aria-label]="toast.message"
        >
          <div class="toast__border"></div>
          <div class="toast__icon" aria-hidden="true">
            @switch (toast.type) {
              @case ('success') { <span>✓</span> }
              @case ('error')   { <span>✕</span> }
              @case ('warning') { <span>⚠</span> }
              @case ('info')    { <span>ℹ</span> }
            }
          </div>
          <p class="toast__message">{{ toast.message }}</p>
          <button
            class="toast__close"
            (click)="dismissClick($event, toast)"
            aria-label="Zatvori obaveštenje"
            type="button"
          >×</button>
          <div class="toast__progress" aria-hidden="true">
            <div
              class="toast__progress-bar"
              [style.animation-duration.ms]="toast.duration"
              [class.toast__progress-bar--running]="toast.state === 'visible'"
            ></div>
          </div>
        </div>
      }
    </div>
  `,
  styles: [`
    .toast-container {
      position: fixed;
      top: 72px; /* below desktop navbar */
      left: 50%;
      transform: translateX(-50%);
      z-index: 9999;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      pointer-events: none;
      width: 100%;
      max-width: 420px;
      padding: 0 16px;
      box-sizing: border-box;
      /* iOS safe area */
      padding-top: env(safe-area-inset-top, 0px);
    }

    @media (max-width: 767px) {
      .toast-container {
        top: 68px; /* below mobile navbar (60px) + small buffer */
        max-width: 100%;
        padding: 0 12px;
      }
    }

    .toast {
      position: relative;
      display: flex;
      align-items: center;
      gap: 12px;
      width: 100%;
      padding: 14px 44px 17px 16px;
      background: var(--color-surface, #fff);
      border-radius: var(--radius-md, 12px);
      box-shadow: var(--shadow-lg);
      cursor: pointer;
      pointer-events: all;
      overflow: hidden;
      will-change: transform, opacity;
      /* default hidden state — animations transition from this */
      opacity: 0;
      transform: translateY(-16px) scale(0.97);
    }

    /* ── State classes handle the animation ── */
    .toast--entering {
      opacity: 0;
      transform: translateY(-16px) scale(0.97);
    }

    .toast--visible {
      opacity: 1;
      transform: translateY(0) scale(1);
      transition:
        opacity 0.3s cubic-bezier(0.0, 0.0, 0.2, 1),
        transform 0.3s cubic-bezier(0.0, 0.0, 0.2, 1);
    }

    .toast--exiting {
      opacity: 0;
      transform: translateY(-12px) scale(0.97);
      transition:
        opacity 0.2s cubic-bezier(0.4, 0.0, 1, 1),
        transform 0.2s cubic-bezier(0.4, 0.0, 1, 1);
    }

    /* ── Colored left accent border ── */
    .toast__border {
      position: absolute;
      top: 0;
      left: 0;
      bottom: 0;
      width: 4px;
      border-radius: var(--radius-md, 12px) 0 0 var(--radius-md, 12px);
    }

    .toast--success .toast__border { background: var(--color-success, #00C48C); }
    .toast--error   .toast__border { background: var(--color-error, #FF5A5F); }
    .toast--warning .toast__border { background: var(--color-warning, #F5A623); }
    .toast--info    .toast__border { background: var(--color-info, #0EA5E9); }

    /* ── Icon bubble ── */
    .toast__icon {
      flex-shrink: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 28px;
      height: 28px;
      border-radius: var(--radius-full, 9999px);
      font-size: 14px;
      font-weight: 700;
      line-height: 1;
    }

    .toast--success .toast__icon {
      background: rgba(0, 196, 140, 0.12);
      color: var(--color-success, #00C48C);
    }
    .toast--error .toast__icon {
      background: rgba(255, 90, 95, 0.12);
      color: var(--color-error, #FF5A5F);
    }
    .toast--warning .toast__icon {
      background: rgba(245, 166, 35, 0.12);
      color: var(--color-warning, #F5A623);
    }
    .toast--info .toast__icon {
      background: rgba(14, 165, 233, 0.12);
      color: var(--color-info, #0EA5E9);
    }

    /* ── Message text ── */
    .toast__message {
      flex: 1;
      margin: 0;
      font-family: var(--font-family-base, 'Inter', sans-serif);
      font-size: var(--font-size-sm, 0.875rem);
      font-weight: 500;
      line-height: 1.4;
      color: var(--color-text-primary);
    }

    /* ── Close (×) button ── */
    /* 44×44px touch target wraps the visible 24px icon */
    .toast__close {
      position: absolute;
      top: 0;
      right: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 44px;
      height: 44px;
      border: none;
      background: transparent;
      color: var(--color-text-muted);
      font-size: 18px;
      line-height: 1;
      cursor: pointer;
      border-radius: 0 var(--radius-md, 12px) 0 var(--radius-md, 12px);
      padding: 0;
      transition: color 0.15s ease, background 0.15s ease;
      -webkit-tap-highlight-color: transparent;
    }

    .toast__close:hover {
      color: var(--color-text-primary);
      background: var(--color-surface-muted);
    }

    /* ── Progress bar (depletes over toast duration) ── */
    .toast__progress {
      position: absolute;
      bottom: 0;
      left: 0;
      right: 0;
      height: 3px;
      background: var(--color-surface-muted, rgba(0, 0, 0, 0.06));
    }

    .toast__progress-bar {
      height: 100%;
      width: 100%;
      transform-origin: left;
      transform: scaleX(1);
    }

    .toast__progress-bar--running {
      animation: toast-progress-deplete linear forwards;
    }

    @keyframes toast-progress-deplete {
      from { transform: scaleX(1); }
      to   { transform: scaleX(0); }
    }

    .toast--success .toast__progress-bar { background: var(--color-success, #00C48C); }
    .toast--error   .toast__progress-bar { background: var(--color-error, #FF5A5F); }
    .toast--warning .toast__progress-bar { background: var(--color-warning, #F5A623); }
    .toast--info    .toast__progress-bar { background: var(--color-info, #0EA5E9); }

    /* ── Reduced motion: keep opacity only, skip transforms ── */
    @media (prefers-reduced-motion: reduce) {
      .toast--visible,
      .toast--exiting {
        transition: opacity 0.15s ease !important;
        transform: none !important;
      }
      .toast--entering {
        transform: none !important;
      }
      .toast__progress-bar--running {
        animation: none !important;
        transform: scaleX(0);
      }
    }
  `],
})
export class ToastComponent implements OnInit, OnDestroy {
  private readonly toastService = inject(ToastNotificationService);
  private readonly cdr = inject(ChangeDetectorRef);
  private sub?: Subscription;

  toasts: Toast[] = [];

  ngOnInit(): void {
    this.sub = this.toastService.toasts$.subscribe((toasts: Toast[]) => {
      this.toasts = toasts;
      this.cdr.markForCheck();
    });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  dismiss(toast: Toast): void {
    this.toastService.dismiss(toast.id);
  }

  dismissClick(event: MouseEvent, toast: Toast): void {
    event.stopPropagation();
    this.toastService.dismiss(toast.id);
  }
}