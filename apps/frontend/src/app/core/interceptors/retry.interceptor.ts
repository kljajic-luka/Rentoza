import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { isDevMode } from '@angular/core';
import { delay, Observable, of, retry, RetryConfig, throwError, timer } from 'rxjs';
import { catchError, finalize, switchMap } from 'rxjs/operators';

/**
 * Configuration for retry behavior.
 */
interface RetryOptions {
  /** Maximum number of retry attempts */
  maxRetries: number;
  /** Base delay in milliseconds (will be multiplied by attempt number for exponential backoff) */
  baseDelay: number;
  /** Maximum delay in milliseconds */
  maxDelay: number;
  /** HTTP status codes that should trigger a retry */
  retryableStatuses: number[];
  /** HTTP methods that are safe to retry (idempotent) */
  retryableMethods: string[];
}

/**
 * Default retry configuration.
 * - 3 retries for transient failures
 * - Exponential backoff: 1s, 2s, 4s
 * - Retry on 500, 502, 503, 504, 0 (network error)
 */
const DEFAULT_RETRY_OPTIONS: RetryOptions = {
  maxRetries: 3,
  baseDelay: 1000,
  maxDelay: 10000,
  retryableStatuses: [0, 500, 502, 503, 504],
  retryableMethods: ['GET', 'HEAD', 'OPTIONS', 'PUT', 'DELETE'],
};

/**
 * Endpoints that should NEVER be retried (non-idempotent or sensitive).
 */
const NO_RETRY_ENDPOINTS = [
  '/auth/login',
  '/auth/register',
  '/bookings', // POST creates new booking - not idempotent
  '/payments',
  '/disputes/guest-claim',
];

/**
 * HTTP Interceptor that implements retry with exponential backoff for transient failures.
 *
 * <h2>Retry Strategy</h2>
 * <ul>
 *   <li>Only retry idempotent methods (GET, PUT, DELETE, HEAD, OPTIONS)</li>
 *   <li>Only retry transient errors (5xx, network failures)</li>
 *   <li>Use exponential backoff to prevent thundering herd</li>
 *   <li>Respect Retry-After header from server</li>
 *   <li>Skip retry for authentication and payment endpoints</li>
 * </ul>
 *
 * <h2>Example Behavior</h2>
 * <pre>
 * Request fails with 503
 * → Wait 1 second
 * → Retry #1 fails with 503
 * → Wait 2 seconds
 * → Retry #2 fails with 503
 * → Wait 4 seconds
 * → Retry #3 succeeds or fails permanently
 * </pre>
 *
 * @since Phase 5 - Reliability & Monitoring
 */
export const retryInterceptor: HttpInterceptorFn = (req, next) => {
  // Check if this endpoint should be retried
  if (!shouldRetry(req.method, req.url)) {
    return next(req);
  }

  let retryCount = 0;

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      // Check if error is retryable
      if (!isRetryableError(error) || retryCount >= DEFAULT_RETRY_OPTIONS.maxRetries) {
        return throwError(() => error);
      }

      retryCount++;

      // Calculate delay with exponential backoff
      const delay = calculateDelay(retryCount, error);

      if (isDevMode()) {
        console.log(
          `🔄 Retrying ${req.method} ${req.url} (attempt ${retryCount}/${DEFAULT_RETRY_OPTIONS.maxRetries}) after ${delay}ms`,
        );
      }

      // Wait and retry
      return timer(delay).pipe(switchMap(() => next(req)));
    }),
    // Recursive retry handling
    retry({
      count: DEFAULT_RETRY_OPTIONS.maxRetries,
      delay: (error: HttpErrorResponse, retryIndex: number) => {
        if (!isRetryableError(error)) {
          return throwError(() => error);
        }

        const delay = calculateDelay(retryIndex + 1, error);
        if (isDevMode()) {
          console.log(
            `🔄 Retrying ${req.method} ${req.url} (attempt ${retryIndex + 1}/${DEFAULT_RETRY_OPTIONS.maxRetries}) after ${delay}ms`,
          );
        }

        return timer(delay);
      },
    } as RetryConfig),
  );
};

/**
 * Determines if a request should be retried based on method and URL.
 */
function shouldRetry(method: string, url: string): boolean {
  // Only retry idempotent methods
  if (!DEFAULT_RETRY_OPTIONS.retryableMethods.includes(method.toUpperCase())) {
    return false;
  }

  // Skip retry for sensitive endpoints
  if (NO_RETRY_ENDPOINTS.some((endpoint) => url.includes(endpoint))) {
    return false;
  }

  return true;
}

/**
 * Determines if an error is transient and worth retrying.
 */
function isRetryableError(error: HttpErrorResponse): boolean {
  // Network error (status 0)
  if (error.status === 0) {
    return true;
  }

  // Server errors that are typically transient
  return DEFAULT_RETRY_OPTIONS.retryableStatuses.includes(error.status);
}

/**
 * Calculates delay for the given retry attempt.
 * Uses exponential backoff with jitter.
 * Respects Retry-After header if present.
 */
function calculateDelay(attempt: number, error: HttpErrorResponse): number {
  // Check for Retry-After header
  const retryAfter = error.headers?.get('Retry-After');
  if (retryAfter) {
    const retryAfterMs = parseInt(retryAfter, 10) * 1000;
    if (!isNaN(retryAfterMs) && retryAfterMs > 0) {
      return Math.min(retryAfterMs, DEFAULT_RETRY_OPTIONS.maxDelay);
    }
  }

  // Exponential backoff: baseDelay * 2^(attempt-1) + random jitter
  const exponentialDelay = DEFAULT_RETRY_OPTIONS.baseDelay * Math.pow(2, attempt - 1);
  const jitter = Math.random() * 500; // 0-500ms random jitter

  return Math.min(exponentialDelay + jitter, DEFAULT_RETRY_OPTIONS.maxDelay);
}