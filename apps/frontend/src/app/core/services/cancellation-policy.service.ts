import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, catchError, throwError } from 'rxjs';

import { environment } from '@environments/environment';
import {
  CancellationPreview,
  CancellationRequest,
  CancellationResult,
  CancellationReason,
  HostCancellationStats,
} from '@core/models/cancellation.model';

/**
 * Service for Turo-style cancellation policy operations.
 *
 * <p>Provides methods to:
 * <ul>
 *   <li>Preview cancellation consequences before committing</li>
 *   <li>Execute cancellation with penalty/refund processing</li>
 *   <li>Fetch host cancellation statistics for dashboard</li>
 * </ul>
 *
 * <p><b>Security:</b> All endpoints use cookie-based authentication (withCredentials: true).
 * Backend enforces RLS - only booking participants can cancel.
 *
 * @since 2024-01 (Cancellation Policy Migration - Phase 3)
 */
@Injectable({ providedIn: 'root' })
export class CancellationPolicyService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.baseApiUrl}/bookings`;
  private readonly ownerUrl = `${environment.baseApiUrl}/owner`;

  // ==================== PREVIEW ====================

  /**
   * Get a preview of cancellation consequences WITHOUT executing.
   *
   * <p>Shows the user:
   * <ul>
   *   <li>Penalty amount (if any)</li>
   *   <li>Refund amount</li>
   *   <li>Which rule was applied (24h free, remorse window, etc.)</li>
   *   <li>Host penalty info (if HOST is cancelling)</li>
   * </ul>
   *
   * <p><b>Security:</b> Only the booking's renter or car owner can access.
   *
   * @param bookingId Booking to preview cancellation for
   * @returns Observable<CancellationPreview> with financial breakdown
   */
  getPreview(bookingId: number): Observable<CancellationPreview> {
    return this.http
      .get<CancellationPreview>(`${this.baseUrl}/${bookingId}/cancellation-preview`, {
        withCredentials: true,
      })
      .pipe(catchError(this.handleError));
  }

  // ==================== EXECUTE CANCELLATION ====================

  /**
   * Execute the cancellation with full penalty/refund processing.
   *
   * <p>This method:
   * <ul>
   *   <li>Validates booking state and user authorization</li>
   *   <li>Calculates and applies penalties/refunds</li>
   *   <li>Creates immutable audit record</li>
   *   <li>Sends notifications to both parties</li>
   *   <li>Applies host penalty escalation (if HOST cancelling)</li>
   * </ul>
   *
   * <p><b>Idempotency:</b> Calling this on an already-cancelled booking
   * returns the existing cancellation result.
   *
   * @param bookingId Booking to cancel
   * @param request Cancellation reason and optional notes
   * @returns Observable<CancellationResult> with outcome details
   */
  confirmCancellation(
    bookingId: number,
    request: CancellationRequest
  ): Observable<CancellationResult> {
    return this.http
      .post<CancellationResult>(`${this.baseUrl}/${bookingId}/cancel`, request, {
        withCredentials: true,
      })
      .pipe(catchError(this.handleError));
  }

  /**
   * Convenience method to cancel with just a reason (no notes).
   *
   * @param bookingId Booking to cancel
   * @param reason Why the booking is being cancelled
   * @returns Observable<CancellationResult>
   */
  cancelBooking(bookingId: number, reason: CancellationReason): Observable<CancellationResult> {
    return this.confirmCancellation(bookingId, { reason });
  }

  // ==================== HOST STATISTICS ====================

  /**
   * Get the authenticated host's cancellation statistics.
   *
   * <p>Used to display:
   * <ul>
   *   <li>Current penalty tier on dashboard</li>
   *   <li>Warning before cancellation ("This is your 2nd cancellation...")</li>
   *   <li>Suspension status (if applicable)</li>
   * </ul>
   *
   * <p><b>Security:</b> Returns only the authenticated user's stats.
   * Requires OWNER or ADMIN role.
   *
   * @returns Observable<HostCancellationStats> with tier and suspension info
   */
  getHostStats(): Observable<HostCancellationStats> {
    return this.http
      .get<HostCancellationStats>(`${this.ownerUrl}/cancellation-stats`, {
        withCredentials: true,
      })
      .pipe(catchError(this.handleError));
  }

  // ==================== ERROR HANDLING ====================

  /**
   * Handle HTTP errors with user-friendly messages.
   */
  private handleError(error: HttpErrorResponse): Observable<never> {
    let message = 'Došlo je do greške. Pokušajte ponovo.';

    if (error.error instanceof ErrorEvent) {
      // Client-side error
      message = `Greška: ${error.error.message}`;
    } else {
      // Server-side error
      switch (error.status) {
        case 400:
          message = error.error?.message || 'Neispravan zahtev.';
          break;
        case 403:
          message = 'Nemate dozvolu za ovu akciju.';
          break;
        case 404:
          message = 'Rezervacija nije pronađena.';
          break;
        case 409:
          message = error.error?.message || 'Rezervacija je već otkazana.';
          break;
        case 500:
          message = 'Serverska greška. Pokušajte ponovo kasnije.';
          break;
      }
    }

    console.error('CancellationPolicyService error:', error);
    return throwError(() => new Error(message));
  }
}
