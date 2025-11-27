import { Injectable, inject } from '@angular/core';
import { ToastrService, IndividualConfig } from 'ngx-toastr';
import { Observable, Subject } from 'rxjs';

/**
 * Centralized Toast Notification Service - UX Messaging Polish
 *
 * Purpose:
 * - Provides semantic methods for user notifications
 * - Ensures consistent styling, timing, and positioning across the app
 * - XSS-safe: ngx-toastr escapes HTML by default (enableHtml: false)
 * - Single source of truth for notification behavior
 *
 * Design Principles (Turo/Airbnb Standard):
 * - User-centric: Messages describe outcomes, not technical state
 * - Empathetic: Friendly, helpful language in Serbian
 * - Non-blocking: Toasts for status updates, modals only for destructive confirmations
 * - Consistent: Same duration, position, and styling everywhere
 *
 * Security:
 * - All input strings treated as plain text (no HTML injection)
 * - No raw error messages, stack traces, or SQL dumps displayed
 * - Never log sensitive data to console from user-facing methods
 *
 * @example
 * // Success notification (auto-dismiss 3s)
 * this.toast.success('Vaš profil je ažuriran.');
 *
 * // Error notification (longer timeout 6s)
 * this.toast.error('Pogrešan email ili lozinka. Pokušajte ponovo.');
 *
 * // Semantic methods
 * this.toast.sessionExpired();
 * this.toast.serverError();
 * this.toast.loginRequired('pristupili ovoj funkciji');
 */
@Injectable({
  providedIn: 'root',
})
export class ToastService {
  private readonly toastr = inject(ToastrService);

  // ============================================================
  // Default configurations for each notification type
  // ============================================================

  private readonly successConfig: Partial<IndividualConfig> = {
    timeOut: 2500,
    progressBar: false,
    closeButton: false,
    tapToDismiss: true,
    positionClass: 'toast-top-right',
  };

  private readonly errorConfig: Partial<IndividualConfig> = {
    timeOut: 5000,
    progressBar: false,
    closeButton: true,
    tapToDismiss: true,
    positionClass: 'toast-top-right',
  };

  private readonly warningConfig: Partial<IndividualConfig> = {
    timeOut: 3500,
    progressBar: false,
    closeButton: false,
    tapToDismiss: true,
    positionClass: 'toast-top-right',
  };

  private readonly infoConfig: Partial<IndividualConfig> = {
    timeOut: 3000,
    progressBar: false,
    closeButton: false,
    tapToDismiss: true,
    positionClass: 'toast-top-right',
  };

  // ============================================================
  // Core Public Methods (Concise Naming)
  // ============================================================

  /**
   * Show success notification (green, auto-dismiss 3s)
   * Use for: Completed actions, saved changes, successful submissions
   */
  success(message: string, title?: string): void {
    this.toastr.success(message, title, this.successConfig);
  }

  /**
   * Show error notification (red, 6s timeout, dismissible)
   * Use for: Failed actions, validation errors, connection issues
   */
  error(message: string, title?: string): void {
    this.toastr.error(message, title, this.errorConfig);
  }

  /**
   * Show warning notification (amber/yellow, 4s timeout)
   * Use for: Validation hints, permission issues, pending actions
   */
  warning(message: string, title?: string): void {
    this.toastr.warning(message, title, this.warningConfig);
  }

  /**
   * Show info notification (blue, auto-dismiss 4s)
   * Use for: Status updates, tips, neutral information
   */
  info(message: string, title?: string): void {
    this.toastr.info(message, title, this.infoConfig);
  }

  // ============================================================
  // Semantic Methods for Common Scenarios
  // ============================================================

  /**
   * Session expired notification
   */
  sessionExpired(): void {
    this.toastr.info('Sesija istekla. Prijavite se ponovo.', undefined, this.infoConfig);
  }

  /**
   * Server error notification (500-series)
   */
  serverError(): void {
    this.toastr.error('Servis nedostupan. Pokušajte kasnije.', undefined, this.errorConfig);
  }

  /**
   * Network/connection error notification
   */
  networkError(): void {
    this.toastr.error('Nema konekcije. Proverite internet.', undefined, this.errorConfig);
  }

  /**
   * Permission denied notification (403 Forbidden)
   */
  forbidden(): void {
    this.toastr.warning('Nemate dozvolu za ovu akciju.', undefined, this.warningConfig);
  }

  /**
   * Not found notification (404)
   */
  notFound(): void {
    this.toastr.warning('Resurs nije pronađen.', undefined, this.warningConfig);
  }

  /**
   * Profile saved successfully
   */
  profileSaved(): void {
    this.toastr.success('Izmene sačuvane.', undefined, this.successConfig);
  }

  /**
   * Booking confirmed notification (Instant Booking)
   */
  bookingConfirmed(): void {
    this.toastr.success('Rezervacija potvrđena!', undefined, this.successConfig);
  }

  /**
   * Login required notification
   * @param message Optional custom message
   */
  loginRequired(message?: string): void {
    const msg = message || 'Prijavite se da nastavite.';
    this.toastr.info(msg, undefined, this.infoConfig);
  }

  /**
   * Validation error notification
   * @param message Specific validation message
   */
  validationError(message?: string): void {
    const msg = message || 'Proverite unete podatke.';
    this.toastr.warning(msg, undefined, this.warningConfig);
  }

  /**
   * Conflict error notification (409)
   * @param message Specific conflict message
   */
  conflictError(message: string): void {
    this.toastr.warning(message, undefined, this.warningConfig);
  }

  // ============================================================
  // Utility Methods
  // ============================================================

  /**
   * Clear all visible toasts
   */
  clear(): void {
    this.toastr.clear();
  }

  /**
   * Show confirmation dialog (returns Observable<boolean>)
   * For destructive actions like delete, cancel booking, etc.
   */
  confirm(message: string, title?: string): Observable<boolean> {
    const result$ = new Subject<boolean>();

    // For now, use native confirm - can be replaced with custom modal
    const confirmed = window.confirm(message);
    result$.next(confirmed);
    result$.complete();

    return result$.asObservable();
  }
}
