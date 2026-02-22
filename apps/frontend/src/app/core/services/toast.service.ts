import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ToastNotificationService } from './toast-notification.service';
import { ConfirmDialogService } from './confirm-dialog.service';

/**
 * Centralized Toast Notification Service - UX Messaging Polish
 *
 * Purpose:
 * - Provides semantic methods for user notifications
 * - Delegates to ToastNotificationService (our custom animated toast system)
 * - Backward compatible: all existing call sites continue to work unchanged
 * - Single source of truth for notification behavior
 *
 * Design Principles (Turo/Airbnb Standard):
 * - User-centric: Messages describe outcomes, not technical state
 * - Empathetic: Friendly, helpful language in Serbian
 * - Non-blocking: Toasts for status updates, modals only for destructive confirmations
 * - Consistent: Same duration, position, and styling everywhere
 *
 * @example
 * this.toast.success('Vaš profil je ažuriran.');
 * this.toast.error('Pogrešan email ili lozinka. Pokušajte ponovo.');
 * this.toast.sessionExpired();
 */
@Injectable({
  providedIn: 'root',
})
export class ToastService {
  private readonly notifications = inject(ToastNotificationService);
  private readonly confirmDialog = inject(ConfirmDialogService);

  // ============================================================
  // Core Public Methods
  // ============================================================

  /** Show success notification (green, auto-dismiss 4s) */
  success(message: string, _title?: string): void {
    this.notifications.success(message);
  }

  /** Show error notification (red, 5s timeout, dismissible) */
  error(message: string, _title?: string): void {
    this.notifications.error(message);
  }

  /** Show warning notification (amber/yellow, 4s timeout) */
  warning(message: string, _title?: string): void {
    this.notifications.warning(message);
  }

  /** Show info notification (blue, auto-dismiss 4s) */
  info(message: string, _title?: string): void {
    this.notifications.info(message);
  }

  // ============================================================
  // Semantic Methods for Common Scenarios
  // ============================================================

  /** Session expired notification */
  sessionExpired(): void {
    this.notifications.info('Sesija istekla. Prijavite se ponovo.');
  }

  /** Server error notification (500-series) */
  serverError(): void {
    this.notifications.error('Servis nedostupan. Pokušajte kasnije.');
  }

  /** Network/connection error notification */
  networkError(): void {
    this.notifications.error('Nema konekcije. Proverite internet.');
  }

  /** Permission denied notification (403 Forbidden) */
  forbidden(): void {
    this.notifications.warning('Nemate dozvolu za ovu akciju.');
  }

  /** Not found notification (404) */
  notFound(): void {
    this.notifications.warning('Resurs nije pronađen.');
  }

  /** Profile saved successfully */
  profileSaved(): void {
    this.notifications.success('Izmene sačuvane.');
  }

  /** Booking confirmed notification (Instant Booking) */
  bookingConfirmed(): void {
    this.notifications.success('Rezervacija potvrđena!');
  }

  /** Login required notification */
  loginRequired(message?: string): void {
    this.notifications.info(message || 'Prijavite se da nastavite.');
  }

  /** Validation error notification */
  validationError(message?: string): void {
    this.notifications.warning(message || 'Proverite unete podatke.');
  }

  /** Conflict error notification (409) */
  conflictError(message: string): void {
    this.notifications.warning(message);
  }

  /**
   * User overlap booking error (One Driver, One Car constraint).
   * Shows when user tries to book two cars for overlapping dates.
   */
  userOverlapError(): void {
    this.notifications.warning(
      'Ne možete rezervisati dva vozila u isto vreme. Već imate aktivnu ili čekajuću rezervaciju za ovaj period.',
      6000,
    );
  }

  /** Car unavailable error (car already booked) */
  carUnavailableError(): void {
    this.notifications.warning(
      'Ovaj automobil je već rezervisan za izabrane datume. Molimo izaberite druge datume.',
    );
  }

  // ============================================================
  // Utility Methods
  // ============================================================

  /** Clear all visible toasts */
  clear(): void {
    this.notifications.clearAll();
  }

  /**
   * Show confirmation dialog (returns Observable<boolean>).
   * For destructive actions like delete, cancel booking, etc.
   */
  confirm(message: string, title?: string): Observable<boolean> {
    return this.confirmDialog.open({ message, title, danger: true });
  }
}
