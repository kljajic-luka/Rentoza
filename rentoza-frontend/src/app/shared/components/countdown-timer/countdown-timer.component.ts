import {
  Component,
  Input,
  OnInit,
  OnDestroy,
  ChangeDetectionStrategy,
  signal,
  computed,
  inject,
  NgZone,
} from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Countdown Timer Component - Shared Reusable Component
 *
 * Purpose:
 * - Display live countdown to a target timestamp
 * - Used in Owner Dashboard (pending request deadlines)
 * - Used in Renter Booking Detail (request expiry visibility)
 * - Future: Notification panels, admin dashboards
 *
 * Features:
 * - Real-time updates every second via setInterval
 * - Zone-safe: Runs outside Angular zone for performance
 * - Automatic teardown on component destruction
 * - Dynamic CSS classes based on urgency level
 * - Configurable display formats
 *
 * Inputs:
 * - targetDate: ISO string or Date object
 * - showSeconds: Whether to display seconds (default: true)
 * - compactMode: Short format "2h 15m" vs full "02:15:30" (default: false)
 *
 * CSS Classes Applied:
 * - .countdown-expired: Time has passed
 * - .countdown-urgent: < 1 hour remaining (red)
 * - .countdown-warning: < 4 hours remaining (orange)
 * - .countdown-normal: >= 4 hours remaining (green)
 *
 * Usage:
 * <app-countdown-timer
 *   [targetDate]="booking.decisionDeadlineAt"
 *   [showSeconds]="false"
 *   [compactMode]="true">
 * </app-countdown-timer>
 */
@Component({
  selector: 'app-countdown-timer',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="countdown-timer"
      [ngClass]="urgencyClass()"
      [attr.aria-label]="'Preostalo vreme: ' + displayText()"
    >
      {{ displayText() }}
    </span>
  `,
  styles: [
    `
      .countdown-timer {
        font-family: 'Roboto Mono', monospace;
        font-weight: 500;
        padding: 4px 8px;
        border-radius: 4px;
        display: inline-flex;
        align-items: center;
        gap: 4px;
      }

      .countdown-normal {
        color: #2e7d32;
        background-color: rgba(46, 125, 50, 0.1);
      }

      .countdown-warning {
        color: #ef6c00;
        background-color: rgba(239, 108, 0, 0.1);
      }

      .countdown-urgent {
        color: #c62828;
        background-color: rgba(198, 40, 40, 0.15);
        animation: pulse 1s ease-in-out infinite;
      }

      .countdown-expired {
        color: #757575;
        background-color: rgba(117, 117, 117, 0.1);
        text-decoration: line-through;
      }

      @keyframes pulse {
        0%,
        100% {
          opacity: 1;
        }
        50% {
          opacity: 0.7;
        }
      }
    `,
  ],
})
export class CountdownTimerComponent implements OnInit, OnDestroy {
  private readonly ngZone = inject(NgZone);
  private intervalId: ReturnType<typeof setInterval> | null = null;

  /**
   * Target date/time for countdown.
   * Accepts ISO 8601 string (from backend) or JavaScript Date object.
   */
  @Input({ required: true }) targetDate!: string | Date;

  /**
   * Whether to display seconds in the countdown.
   * Set to false for less visual noise in dashboards.
   */
  @Input() showSeconds = true;

  /**
   * Compact mode: "2h 15m" instead of "02:15:30"
   * Useful for space-constrained UI like chips/badges.
   */
  @Input() compactMode = false;

  /**
   * Update interval in milliseconds.
   * Default 1000ms (1 second). Increase for lower CPU usage if seconds aren't shown.
   */
  @Input() updateInterval = 1000;

  // Signal to hold remaining milliseconds (triggers reactive updates)
  private readonly remainingMs = signal<number>(0);

  // Computed signal for display text
  protected readonly displayText = computed(() => {
    const ms = this.remainingMs();

    if (ms <= 0) {
      return 'Isteklo'; // "Expired" in Serbian
    }

    const totalSeconds = Math.floor(ms / 1000);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;

    if (this.compactMode) {
      // Compact format: "2h 15m" or "15m 30s" or "30s"
      if (hours > 24) {
        const days = Math.floor(hours / 24);
        return `${days}d ${hours % 24}h`;
      }
      if (hours > 0) {
        return `${hours}h ${minutes}m`;
      }
      if (minutes > 0) {
        return this.showSeconds ? `${minutes}m ${seconds}s` : `${minutes}m`;
      }
      return `${seconds}s`;
    }

    // Full format: "HH:MM:SS" or "HH:MM"
    const pad = (n: number) => n.toString().padStart(2, '0');

    if (hours > 99) {
      // For very long durations, show days
      const days = Math.floor(hours / 24);
      const remainingHours = hours % 24;
      return `${days}d ${pad(remainingHours)}:${pad(minutes)}`;
    }

    if (this.showSeconds) {
      return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
    }

    return `${pad(hours)}:${pad(minutes)}`;
  });

  // Computed signal for urgency CSS class
  protected readonly urgencyClass = computed(() => {
    const ms = this.remainingMs();

    if (ms <= 0) {
      return 'countdown-expired';
    }

    const hoursRemaining = ms / (1000 * 60 * 60);

    if (hoursRemaining <= 1) {
      return 'countdown-urgent';
    }

    if (hoursRemaining <= 4) {
      return 'countdown-warning';
    }

    return 'countdown-normal';
  });

  ngOnInit(): void {
    this.calculateRemaining();
    this.startTimer();
  }

  ngOnDestroy(): void {
    this.stopTimer();
  }

  /**
   * Calculate remaining milliseconds from now to target.
   */
  private calculateRemaining(): void {
    const target =
      typeof this.targetDate === 'string' ? new Date(this.targetDate) : this.targetDate;

    const now = new Date();
    const diff = target.getTime() - now.getTime();

    this.remainingMs.set(diff);
  }

  /**
   * Start the countdown timer.
   * Runs outside Angular zone for performance (prevents CD cycles).
   */
  private startTimer(): void {
    // Stop existing timer if any
    this.stopTimer();

    // Run interval outside Angular zone to avoid triggering change detection
    this.ngZone.runOutsideAngular(() => {
      this.intervalId = setInterval(() => {
        // Calculate new remaining time
        const target =
          typeof this.targetDate === 'string' ? new Date(this.targetDate) : this.targetDate;

        const now = new Date();
        const diff = target.getTime() - now.getTime();

        // Update signal inside Angular zone (triggers CD only for this component due to OnPush)
        this.ngZone.run(() => {
          this.remainingMs.set(diff);

          // Stop timer if expired
          if (diff <= 0) {
            this.stopTimer();
          }
        });
      }, this.updateInterval);
    });
  }

  /**
   * Stop and clean up the timer.
   */
  private stopTimer(): void {
    if (this.intervalId !== null) {
      clearInterval(this.intervalId);
      this.intervalId = null;
    }
  }
}
