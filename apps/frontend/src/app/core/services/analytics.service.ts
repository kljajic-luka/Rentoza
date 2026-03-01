import { Injectable, inject, OnDestroy } from '@angular/core';

import { LoggerService } from './logger.service';
import { environment } from '@environments/environment';

interface AnalyticsEvent {
  event: string;
  properties?: Record<string, unknown>;
  timestamp: string;
  url: string;
  sessionId: string;
}

/**
 * Booking event telemetry service.
 *
 * Tracks key booking lifecycle events for product analytics:
 * - booking.created, booking.approved, booking.declined, booking.cancelled
 * - checkin.host_complete, checkin.handshake_complete
 * - checkout.guest_complete, checkout.completed
 *
 * Architecture:
 * - Queues events in memory, flushes every 30s via navigator.sendBeacon
 * - Uses fetch() not HttpClient (avoids circular dependency with interceptors)
 * - Auto-flushes on page visibility change (tab close / background)
 * - Production only: dev mode logs to console but doesn't send
 */
@Injectable({ providedIn: 'root' })
export class AnalyticsService implements OnDestroy {
  private readonly logger = inject(LoggerService);

  private queue: AnalyticsEvent[] = [];
  private readonly maxQueueSize = 20;
  private readonly flushIntervalMs = 30000;
  private flushTimer: any = null;
  private readonly sessionId = this.generateSessionId();
  private readonly telemetryUrl = `${environment.baseApiUrl}/telemetry/events`;
  private visibilityHandler: (() => void) | null = null;

  constructor() {
    if (environment.production) {
      this.startFlushInterval();
      this.registerVisibilityHandler();
    }
  }

  ngOnDestroy(): void {
    if (this.flushTimer) {
      clearInterval(this.flushTimer);
      this.flushTimer = null;
    }
    if (this.visibilityHandler) {
      document.removeEventListener('visibilitychange', this.visibilityHandler);
      this.visibilityHandler = null;
    }
    // Final flush on destroy
    this.flush();
  }

  /**
   * Track a booking lifecycle event.
   *
   * @param event Event name (e.g., 'booking.created', 'checkin.host_complete')
   * @param properties Optional metadata (bookingId, carId, etc.)
   */
  track(event: string, properties?: Record<string, unknown>): void {
    const analyticsEvent: AnalyticsEvent = {
      event,
      properties,
      timestamp: new Date().toISOString(),
      url: typeof window !== 'undefined' ? window.location.href : '',
      sessionId: this.sessionId,
    };

    this.logger.debug('[Analytics]', event, properties);
    this.queue.push(analyticsEvent);

    // Auto-flush if queue is full
    if (this.queue.length >= this.maxQueueSize) {
      this.flush();
    }
  }

  private flush(): void {
    if (this.queue.length === 0) return;

    const batch = [...this.queue];
    this.queue = [];

    try {
      const payload = JSON.stringify({ events: batch });

      if (navigator.sendBeacon) {
        const blob = new Blob([payload], { type: 'application/json' });
        navigator.sendBeacon(this.telemetryUrl, blob);
      } else {
        // Fallback for browsers without sendBeacon
        fetch(this.telemetryUrl, {
          method: 'POST',
          body: payload,
          headers: { 'Content-Type': 'application/json' },
          keepalive: true,
          credentials: 'include',
        }).catch(() => {
          // Silently fail — telemetry is best-effort
        });
      }
    } catch {
      // Silently fail — telemetry must never break the app
    }
  }

  private startFlushInterval(): void {
    this.flushTimer = setInterval(() => this.flush(), this.flushIntervalMs);
  }

  private registerVisibilityHandler(): void {
    this.visibilityHandler = () => {
      if (document.visibilityState === 'hidden') {
        this.flush();
      }
    };
    document.addEventListener('visibilitychange', this.visibilityHandler);
  }

  private generateSessionId(): string {
    return `${Date.now().toString(36)}-${Math.random().toString(36).substring(2, 9)}`;
  }
}
