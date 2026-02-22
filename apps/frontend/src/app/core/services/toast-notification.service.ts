import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

export type ToastType = 'success' | 'error' | 'warning' | 'info';
export type ToastState = 'entering' | 'visible' | 'exiting' | 'dismissed';

export interface Toast {
  id: number;
  type: ToastType;
  message: string;
  duration: number;
  state: ToastState;
}

/**
 * Custom Toast Notification Service — Category 4 Interactions
 *
 * Manages toast lifecycle: enter → visible → auto-dismiss → exit → remove.
 * Max 3 visible toasts; extras are queued.
 * Works with ToastComponent (top-center overlay in app.component.html).
 *
 * This service is NOT the same as the existing ToastService (ngx-toastr wrapper).
 * After migration, the old ToastService delegates to this one.
 */
@Injectable({ providedIn: 'root' })
export class ToastNotificationService {
  private nextId = 0;
  private readonly _toasts = new BehaviorSubject<Toast[]>([]);
  private readonly queue: Toast[] = [];
  private readonly timers = new Map<number, ReturnType<typeof setTimeout>>();

  /** Observable of currently visible toasts (max 3). */
  readonly toasts$ = this._toasts.asObservable();

  // ─── Public API ────────────────────────────────────────

  success(message: string, duration: number = 4000): void {
    this.add('success', message, duration);
  }

  error(message: string, duration: number = 5000): void {
    this.add('error', message, duration);
  }

  warning(message: string, duration: number = 4000): void {
    this.add('warning', message, duration);
  }

  info(message: string, duration: number = 4000): void {
    this.add('info', message, duration);
  }

  dismiss(id: number): void {
    this.startExit(id);
  }

  clearAll(): void {
    const current = this._toasts.value;
    current.forEach((t) => this.clearTimer(t.id));
    this.queue.length = 0;
    this._toasts.next([]);
  }

  // ─── Internal ──────────────────────────────────────────

  private add(type: ToastType, message: string, duration: number): void {
    const toast: Toast = {
      id: this.nextId++,
      type,
      message,
      duration,
      state: 'entering',
    };

    const current = this._toasts.value;

    if (current.length >= 3) {
      // Queue it; it will be shown when a slot opens
      this.queue.push(toast);
      return;
    }

    this.show(toast);
  }

  private show(toast: Toast): void {
    const current = [...this._toasts.value, toast];
    this._toasts.next(current);

    // After a microtask, transition to 'visible' so CSS animation triggers
    requestAnimationFrame(() => {
      this.setState(toast.id, 'visible');
      this.scheduleAutoDismiss(toast);
    });
  }

  private scheduleAutoDismiss(toast: Toast): void {
    const timer = setTimeout(() => {
      this.startExit(toast.id);
    }, toast.duration);
    this.timers.set(toast.id, timer);
  }

  private startExit(id: number): void {
    this.clearTimer(id);
    this.setState(id, 'exiting');

    // Remove after exit animation completes (200ms)
    setTimeout(() => {
      this.remove(id);
    }, 200);
  }

  private remove(id: number): void {
    const current = this._toasts.value.filter((t) => t.id !== id);
    this._toasts.next(current);

    // Show queued toast if any
    if (this.queue.length > 0 && current.length < 3) {
      const next = this.queue.shift()!;
      this.show(next);
    }
  }

  private setState(id: number, state: ToastState): void {
    const current = this._toasts.value.map((t) =>
      t.id === id ? { ...t, state } : t,
    );
    this._toasts.next(current);
  }

  private clearTimer(id: number): void {
    const timer = this.timers.get(id);
    if (timer) {
      clearTimeout(timer);
      this.timers.delete(id);
    }
  }
}