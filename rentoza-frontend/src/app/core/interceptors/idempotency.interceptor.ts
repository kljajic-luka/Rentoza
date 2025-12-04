import { HttpInterceptorFn, HttpRequest, HttpErrorResponse } from '@angular/common/http';
import { tap, catchError, throwError } from 'rxjs';

/**
 * Idempotency Key Storage
 *
 * Manages idempotency keys for mutation requests to prevent duplicate operations.
 * Keys are stored in localStorage for persistence across page reloads (important
 * for network retries after connectivity loss).
 *
 * Phase 1 Critical Fix: Prevents duplicate payments, bookings, and state transitions.
 */
class IdempotencyKeyStore {
  private readonly STORAGE_PREFIX = 'idempotency_';
  private readonly KEY_TTL_MS = 24 * 60 * 60 * 1000; // 24 hours (matches server TTL)

  /**
   * Get or generate an idempotency key for an operation.
   * If a key exists for this operation, return it (for retry scenarios).
   * Otherwise, generate a new one.
   *
   * @param operationId Unique identifier for the operation (e.g., "booking_123_handshake")
   * @returns UUID v4 idempotency key
   */
  getOrCreate(operationId: string): string {
    const storageKey = this.STORAGE_PREFIX + operationId;
    const stored = this.getStoredKey(storageKey);

    if (stored) {
      return stored;
    }

    const newKey = this.generateUUID();
    this.storeKey(storageKey, newKey);
    return newKey;
  }

  /**
   * Clear idempotency key after successful completion.
   * Allows future operations on the same resource.
   */
  clear(operationId: string): void {
    const storageKey = this.STORAGE_PREFIX + operationId;
    try {
      localStorage.removeItem(storageKey);
    } catch (e) {
      // localStorage may be unavailable in incognito mode
      console.debug('[Idempotency] localStorage clear failed:', e);
    }
  }

  /**
   * Clear all expired keys (housekeeping).
   * Called periodically to prevent storage bloat.
   */
  clearExpired(): void {
    try {
      const now = Date.now();
      const keysToRemove: string[] = [];

      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (key?.startsWith(this.STORAGE_PREFIX)) {
          const value = localStorage.getItem(key);
          if (value) {
            try {
              const parsed = JSON.parse(value);
              if (parsed.timestamp && now - parsed.timestamp > this.KEY_TTL_MS) {
                keysToRemove.push(key);
              }
            } catch {
              // Invalid format, remove
              keysToRemove.push(key);
            }
          }
        }
      }

      keysToRemove.forEach((key) => localStorage.removeItem(key));

      if (keysToRemove.length > 0) {
        console.debug(`[Idempotency] Cleared ${keysToRemove.length} expired keys`);
      }
    } catch (e) {
      console.debug('[Idempotency] localStorage cleanup failed:', e);
    }
  }

  private getStoredKey(storageKey: string): string | null {
    try {
      const value = localStorage.getItem(storageKey);
      if (value) {
        const parsed = JSON.parse(value);
        // Check if expired
        if (parsed.timestamp && Date.now() - parsed.timestamp < this.KEY_TTL_MS) {
          return parsed.key;
        }
        // Expired, remove
        localStorage.removeItem(storageKey);
      }
    } catch (e) {
      console.debug('[Idempotency] localStorage read failed:', e);
    }
    return null;
  }

  private storeKey(storageKey: string, key: string): void {
    try {
      const value = JSON.stringify({
        key,
        timestamp: Date.now(),
      });
      localStorage.setItem(storageKey, value);
    } catch (e) {
      console.debug('[Idempotency] localStorage write failed:', e);
    }
  }

  /**
   * Generate a UUID v4.
   * Uses crypto.randomUUID() if available, falls back to manual generation.
   */
  private generateUUID(): string {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
      return crypto.randomUUID();
    }

    // Fallback for older browsers
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0;
      const v = c === 'x' ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }
}

// Singleton store instance
const idempotencyStore = new IdempotencyKeyStore();

// Run cleanup on module load
idempotencyStore.clearExpired();

/**
 * Idempotency HTTP Interceptor (Functional)
 *
 * Automatically adds X-Idempotency-Key header to mutation requests (POST, PUT, PATCH, DELETE)
 * on idempotency-enabled endpoints.
 *
 * ## Behavior
 *
 * 1. For POST/PUT/PATCH/DELETE on enabled endpoints:
 *    - Generate or retrieve existing idempotency key
 *    - Add X-Idempotency-Key header
 *    - On 2xx success: clear the key (operation complete)
 *    - On 409 Conflict: request is already processing, don't retry immediately
 *    - On 4xx/5xx error: keep key for potential retry
 *
 * 2. For GET requests or non-enabled endpoints:
 *    - Pass through without modification
 *
 * ## Idempotency-Enabled Endpoints
 *
 * Check-in workflow:
 *   - bookings/:id/check-in/host/complete
 *   - bookings/:id/check-in/guest/condition-ack
 *   - bookings/:id/check-in/handshake
 *
 * Booking management:
 *   - bookings (POST - create booking)
 *   - bookings/:id/approve
 *   - bookings/:id/decline
 *
 * Payment processing:
 *   - payments/:id/capture
 *
 * @see IdempotencyService in backend
 */
export const idempotencyInterceptor: HttpInterceptorFn = (req, next) => {
  // Only apply to mutation methods
  if (!isMutationMethod(req.method)) {
    return next(req);
  }

  // Check if endpoint requires idempotency
  const operationId = getOperationId(req);
  if (!operationId) {
    return next(req);
  }

  // Get or create idempotency key
  const idempotencyKey = idempotencyStore.getOrCreate(operationId);

  // Clone request with idempotency header
  const clonedReq = req.clone({
    setHeaders: {
      'X-Idempotency-Key': idempotencyKey,
    },
  });

  console.debug(
    `[Idempotency] Request ${operationId} with key ${idempotencyKey.substring(0, 8)}...`
  );

  return next(clonedReq).pipe(
    tap((event) => {
      // On successful response, clear the key
      if ('status' in event && event.status >= 200 && event.status < 300) {
        idempotencyStore.clear(operationId);
        console.debug(`[Idempotency] Cleared key for successful operation: ${operationId}`);
      }
    }),
    catchError((error: HttpErrorResponse) => {
      // On 409 Conflict, the request is already being processed
      // Keep the key but don't retry immediately
      if (error.status === 409) {
        console.warn(`[Idempotency] Operation ${operationId} already in progress (409)`);
      }

      // On other errors, keep the key for potential retry
      // (the same idempotency key will be used on retry)

      return throwError(() => error);
    })
  );
};

/**
 * Check if HTTP method is a mutation.
 */
function isMutationMethod(method: string): boolean {
  return ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method.toUpperCase());
}

/**
 * Get a stable operation ID from the request.
 * Returns null if endpoint doesn't require idempotency.
 */
function getOperationId(req: HttpRequest<unknown>): string | null {
  const url = req.url;

  // Check-in workflow endpoints (critical for idempotency)
  const checkInHostComplete = url.match(/\/api\/bookings\/(\d+)\/check-in\/host\/complete/);
  if (checkInHostComplete) {
    return `booking_${checkInHostComplete[1]}_host_complete`;
  }

  const checkInGuestAck = url.match(/\/api\/bookings\/(\d+)\/check-in\/guest\/condition-ack/);
  if (checkInGuestAck) {
    return `booking_${checkInGuestAck[1]}_guest_ack`;
  }

  const checkInHandshake = url.match(/\/api\/bookings\/(\d+)\/check-in\/handshake/);
  if (checkInHandshake) {
    return `booking_${checkInHandshake[1]}_handshake`;
  }

  // Checkout workflow endpoints
  const checkoutGuestComplete = url.match(/\/api\/bookings\/(\d+)\/checkout\/guest\/complete/);
  if (checkoutGuestComplete) {
    return `booking_${checkoutGuestComplete[1]}_checkout_guest`;
  }

  const checkoutHostComplete = url.match(/\/api\/bookings\/(\d+)\/checkout\/host\/complete/);
  if (checkoutHostComplete) {
    return `booking_${checkoutHostComplete[1]}_checkout_host`;
  }

  const checkoutHandshake = url.match(/\/api\/bookings\/(\d+)\/checkout\/handshake/);
  if (checkoutHandshake) {
    return `booking_${checkoutHandshake[1]}_checkout_handshake`;
  }

  // Booking creation (POST /api/bookings)
  if (url.match(/\/api\/bookings\/?$/) && req.method === 'POST') {
    // For booking creation, use request body to create stable ID
    // This handles double-click scenarios where same booking is submitted twice
    const body = req.body as any;
    if (body?.carId && body?.startTime) {
      return `booking_create_${body.carId}_${body.startTime}`;
    }
  }

  // Booking approval/decline
  const bookingApprove = url.match(/\/api\/bookings\/(\d+)\/approve/);
  if (bookingApprove) {
    return `booking_${bookingApprove[1]}_approve`;
  }

  const bookingDecline = url.match(/\/api\/bookings\/(\d+)\/decline/);
  if (bookingDecline) {
    return `booking_${bookingDecline[1]}_decline`;
  }

  // Payment endpoints (if/when implemented)
  const paymentCapture = url.match(/\/api\/payments\/(\d+)\/capture/);
  if (paymentCapture) {
    return `payment_${paymentCapture[1]}_capture`;
  }

  // Not an idempotency-enabled endpoint
  return null;
}

/**
 * Export utilities for manual idempotency key management.
 */
export function clearIdempotencyKey(operationId: string): void {
  idempotencyStore.clear(operationId);
}

export function clearAllIdempotencyKeys(): void {
  try {
    const keysToRemove: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key?.startsWith('idempotency_')) {
        keysToRemove.push(key);
      }
    }
    keysToRemove.forEach((key) => localStorage.removeItem(key));
  } catch (e) {
    console.debug('[Idempotency] clearAll failed:', e);
  }
}
