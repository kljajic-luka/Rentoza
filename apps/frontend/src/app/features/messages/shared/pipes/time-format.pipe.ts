import { Pipe, PipeTransform } from '@angular/core';

/**
 * Pipe to format timestamps for chat display.
 * Supports relative time (just now, 5m, 2h) and absolute time.
 */
@Pipe({
  name: 'timeFormat',
  standalone: true,
})
export class TimeFormatPipe implements PipeTransform {
  transform(timestamp: string | Date | undefined, format: 'relative' | 'time' | 'full' | 'short' = 'relative'): string {
    if (!timestamp) return '';

    try {
      const date = timestamp instanceof Date ? timestamp : new Date(timestamp);
      const now = new Date();

      switch (format) {
        case 'relative':
          return this.formatRelative(date, now);
        case 'time':
        case 'short':
          return this.formatTime(date);
        case 'full':
          return this.formatFull(date, now);
        default:
          return this.formatRelative(date, now);
      }
    } catch {
      return '';
    }
  }

  private formatRelative(date: Date, now: Date): string {
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return 'Just now';
    if (diffMins < 60) return `${diffMins}m`;
    if (diffHours < 24) return `${diffHours}h`;
    if (diffDays < 7) return `${diffDays}d`;

    return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  }

  private formatTime(date: Date): string {
    return date.toLocaleTimeString('en-US', {
      hour: 'numeric',
      minute: '2-digit',
      hour12: true,
    });
  }

  private formatFull(date: Date, now: Date): string {
    const isToday = date.toDateString() === now.toDateString();
    const yesterday = new Date(now);
    yesterday.setDate(yesterday.getDate() - 1);
    const isYesterday = date.toDateString() === yesterday.toDateString();

    if (isToday) {
      return `Today ${this.formatTime(date)}`;
    } else if (isYesterday) {
      return `Yesterday ${this.formatTime(date)}`;
    } else {
      return date.toLocaleDateString('en-US', {
        month: 'short',
        day: 'numeric',
        year: date.getFullYear() !== now.getFullYear() ? 'numeric' : undefined,
      }) + ' ' + this.formatTime(date);
    }
  }
}