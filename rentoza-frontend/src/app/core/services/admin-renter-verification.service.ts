import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { shareReplay, tap } from 'rxjs/operators';

import {
  PagedRenterVerifications,
  AdminVerificationDetails,
  VerificationAuditEvent,
  VerificationQueueParams,
  ApproveVerificationRequest,
  RejectVerificationRequest,
  SuspendVerificationRequest,
} from '@core/models/renter-verification.model';
import { environment } from '@environments/environment';

/**
 * Admin service for managing renter verification queue.
 *
 * Features:
 * - Paginated queue of pending verifications
 * - Document viewing via signed URLs
 * - Approve/reject/suspend workflows
 * - Audit trail retrieval
 *
 * @example
 * ```typescript
 * // In admin component
 * private adminVerification = inject(AdminRenterVerificationService);
 *
 * loadQueue() {
 *   this.adminVerification.getPendingVerifications({ page: 0, size: 20 })
 *     .subscribe(page => this.verifications = page);
 * }
 *
 * approve(userId: number) {
 *   this.adminVerification.approve(userId, { notes: 'Verified manually' })
 *     .subscribe(() => this.loadQueue());
 * }
 * ```
 */
@Injectable({ providedIn: 'root' })
export class AdminRenterVerificationService {
  private readonly http = inject(HttpClient);

  private readonly API_BASE = `${environment.baseApiUrl}/admin/renter-verifications`;

  // ============================================================================
  // QUEUE METHODS
  // ============================================================================

  /**
   * Get paginated list of pending renter verifications.
   *
   * @param params - Query parameters for filtering/pagination
   * @returns Observable of paged results
   */
  getPendingVerifications(
    params: VerificationQueueParams = {},
  ): Observable<PagedRenterVerifications> {
    let httpParams = new HttpParams();

    if (params.page !== undefined) {
      httpParams = httpParams.set('page', params.page.toString());
    }
    if (params.size !== undefined) {
      httpParams = httpParams.set('size', params.size.toString());
    }
    if (params.status) {
      httpParams = httpParams.set('status', params.status);
    }
    if (params.riskLevel) {
      httpParams = httpParams.set('riskLevel', params.riskLevel);
    }
    if (params.sortBy) {
      httpParams = httpParams.set('sortBy', params.sortBy);
    }

    return this.http
      .get<PagedRenterVerifications>(`${this.API_BASE}/pending`, { params: httpParams })
      .pipe(shareReplay({ bufferSize: 1, refCount: true }));
  }

  /**
   * Get full verification details for a specific user.
   * Includes documents, OCR results, and audit trail.
   *
   * @param userId - User ID to retrieve details for
   * @returns Observable of verification details
   */
  getVerificationDetails(userId: number): Observable<AdminVerificationDetails> {
    return this.http
      .get<AdminVerificationDetails>(`${this.API_BASE}/users/${userId}`)
      .pipe(shareReplay({ bufferSize: 1, refCount: true }));
  }

  /**
   * Get audit trail for a user's verification history.
   *
   * @param userId - User ID
   * @returns Observable of audit events
   */
  getAuditTrail(userId: number): Observable<VerificationAuditEvent[]> {
    return this.http
      .get<VerificationAuditEvent[]>(`${this.API_BASE}/users/${userId}/audit`)
      .pipe(shareReplay({ bufferSize: 1, refCount: true }));
  }

  // ============================================================================
  // DOCUMENT ACCESS
  // ============================================================================

  /**
   * Get signed URL for viewing a verification document.
   *
   * SECURITY NOTES:
   * - URL expires in 15 minutes
   * - URL is unique per request (cannot be shared)
   * - Access is logged for compliance
   *
   * @param documentId - Document ID to view
   * @returns Observable with signed URL
   */
  getDocumentSignedUrl(documentId: number): Observable<{ url: string; expiresAt: string }> {
    return this.http
      .post<{
        url: string;
        expiresAt: string;
      }>(`${this.API_BASE}/documents/${documentId}/signed-url`, {})
      .pipe(shareReplay({ bufferSize: 1, refCount: true }));
  }

  // ============================================================================
  // DECISION METHODS
  // ============================================================================

  /**
   * Approve a renter's verification.
   * User can immediately start booking after approval.
   *
   * @param userId - User ID to approve
   * @param request - Optional approval notes
   * @returns Observable completing on success
   */
  approve(userId: number, request: ApproveVerificationRequest = {}): Observable<void> {
    return this.http.post<void>(`${this.API_BASE}/users/${userId}/approve`, request);
  }

  /**
   * Reject a renter's verification.
   * User must re-submit with corrected documents.
   *
   * @param userId - User ID to reject
   * @param request - Rejection reason (shown to user)
   * @returns Observable completing on success
   */
  reject(userId: number, request: RejectVerificationRequest): Observable<void> {
    return this.http.post<void>(`${this.API_BASE}/users/${userId}/reject`, request);
  }

  /**
   * Suspend a user from verification (fraud/abuse).
   * User cannot retry or book cars. Requires manual admin intervention to lift.
   *
   * @param userId - User ID to suspend
   * @param request - Suspension reason (audit trail only, not shown to user)
   * @returns Observable completing on success
   */
  suspend(userId: number, request: SuspendVerificationRequest): Observable<void> {
    return this.http.post<void>(`${this.API_BASE}/users/${userId}/suspend`, request);
  }

  /**
   * Request resubmission from a user.
   * Sets status back to allow new document upload.
   *
   * @param userId - User ID
   * @param reason - Reason for resubmission request
   * @returns Observable completing on success
   */
  requestResubmission(userId: number, reason: string): Observable<void> {
    return this.http.post<void>(`${this.API_BASE}/users/${userId}/request-resubmission`, {
      reason,
    });
  }

  // ============================================================================
  // ANALYTICS
  // ============================================================================

  /**
   * Get verification queue statistics.
   *
   * @returns Observable of queue stats
   */
  getQueueStats(): Observable<VerificationQueueStats> {
    return this.http
      .get<VerificationQueueStats>(`${this.API_BASE}/stats`)
      .pipe(shareReplay({ bufferSize: 1, refCount: true }));
  }
}

// ============================================================================
// ADDITIONAL TYPES
// ============================================================================

/**
 * Verification queue statistics for admin dashboard.
 */
export interface VerificationQueueStats {
  pendingCount: number;
  approvedToday: number;
  rejectedToday: number;
  averageReviewTimeMinutes: number;
  autoApprovalRate: number; // 0.0-1.0
}
