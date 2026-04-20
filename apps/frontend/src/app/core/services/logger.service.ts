import { Injectable } from '@angular/core';
import { environment } from '@environments/environment';

/**
 * Production-safe logging service.
 *
 * In production mode, all console statements are disabled to:
 * - Prevent sensitive data leakage
 * - Improve performance
 * - Reduce bundle size (when used with tree-shaking)
 *
 * In development mode, logs normally to browser console.
 *
 * Usage:
 * ```typescript
 * constructor(private logger: LoggerService) {}
 *
 * this.logger.log('User logged in:', user.email);
 * this.logger.warn('Session expiring soon');
 * this.logger.error('API call failed:', error);
 * ```
 */
@Injectable({
  providedIn: 'root',
})
export class LoggerService {
  private readonly isProduction = environment.production;

  log(...args: any[]): void {
    if (!this.isProduction) {
      console.log(...args);
    }
  }

  warn(...args: any[]): void {
    if (!this.isProduction) {
      console.warn(...args);
    }
  }

  error(...args: any[]): void {
    // Always log errors, even in production (they go to browser console but not exposed to users)
    // In future, these could be sent to a logging service like Sentry
    console.error(...args);
  }

  info(...args: any[]): void {
    if (!this.isProduction) {
      console.info(...args);
    }
  }

  debug(...args: any[]): void {
    if (!this.isProduction) {
      console.debug(...args);
    }
  }

  group(label: string): void {
    if (!this.isProduction) {
      console.group(label);
    }
  }

  groupEnd(): void {
    if (!this.isProduction) {
      console.groupEnd();
    }
  }

  table(data: any): void {
    if (!this.isProduction) {
      console.table(data);
    }
  }
}