import { ErrorHandler, Injectable, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';

import { LoggerService } from '@core/services/logger.service';
import { environment } from '@environments/environment';

/**
 * Global error handler that catches unhandled exceptions.
 *
 * - Logs all unhandled errors via LoggerService
 * - Reports errors to backend via navigator.sendBeacon (fire-and-forget)
 * - Skips HttpErrorResponse (already handled by error.interceptor.ts)
 * - Wrapped in try-catch to ensure the error handler itself never throws
 */
@Injectable()
export class GlobalErrorHandler implements ErrorHandler {
  private readonly logger = inject(LoggerService);

  handleError(error: unknown): void {
    try {
      // Skip HttpErrorResponse — already handled by the HTTP interceptor
      if (error instanceof HttpErrorResponse) {
        return;
      }

      // Extract error details
      const errorDetails = this.extractErrorDetails(error);

      // Always log to console via LoggerService
      this.logger.error('[GlobalErrorHandler]', errorDetails.message, error);

      // Report to backend in production
      if (environment.production) {
        this.reportError(errorDetails);
      }
    } catch (handlerError) {
      // Error handler must never throw
      console.error('[GlobalErrorHandler] Error in error handler:', handlerError);
    }
  }

  private extractErrorDetails(error: unknown): {
    message: string;
    stack?: string;
    timestamp: string;
    url: string;
  } {
    const timestamp = new Date().toISOString();
    const url = typeof window !== 'undefined' ? window.location.href : '';

    if (error instanceof Error) {
      return {
        message: error.message,
        stack: error.stack,
        timestamp,
        url,
      };
    }

    return {
      message: String(error),
      timestamp,
      url,
    };
  }

  private reportError(details: {
    message: string;
    stack?: string;
    timestamp: string;
    url: string;
  }): void {
    try {
      const payload = JSON.stringify({
        type: 'UNHANDLED_ERROR',
        message: details.message,
        stack: details.stack?.substring(0, 2000),
        timestamp: details.timestamp,
        url: details.url,
        userAgent: navigator.userAgent,
      });

      if (navigator.sendBeacon) {
        navigator.sendBeacon(`${environment.baseApiUrl}/telemetry/errors`, payload);
      }
    } catch {
      // Silently fail — reporting is best-effort
    }
  }
}
