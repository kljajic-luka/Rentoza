import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, forkJoin } from 'rxjs';
import { map, shareReplay } from 'rxjs/operators';

import {
  PagedRenterVerifications,
  AdminVerificationDetails,
  DocumentVerificationStatus,
  VerificationAuditEvent,
  VerificationAuditAction,
  VerificationQueueParams,
  ApproveVerificationRequest,
  DocumentAccessGrant,
  DocumentAccessRequest,
  RejectVerificationRequest,
  RenterDocument,
  OcrResults,
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
      .get<BackendPagedRenterVerifications>(`${this.API_BASE}/pending`, { params: httpParams })
      .pipe(map((page) => this.mapPagedVerifications(page)))
      .pipe(shareReplay({ bufferSize: 1, refCount: true }));
  }

  /**
   * Get full verification details for a specific user.
   * Includes documents, derived OCR summary, and mapped audit trail.
   *
   * @param userId - User ID to retrieve details for
   * @returns Observable of verification details
   */
  getVerificationDetails(userId: number): Observable<AdminVerificationDetails> {
    return forkJoin({
      profile: this.http.get<BackendAdminVerificationDetails>(`${this.API_BASE}/users/${userId}`),
      audits: this.http.get<BackendAuditTrailItem[]>(`${this.API_BASE}/users/${userId}/audits`),
    }).pipe(
      map(({ profile, audits }) => this.mapVerificationDetails(profile, audits)),
      shareReplay({ bufferSize: 1, refCount: true }),
    );
  }

  /**
   * Get audit trail for a user's verification history.
   */
  getAuditTrail(userId: number): Observable<VerificationAuditEvent[]> {
    return this.http
      .get<BackendAuditTrailItem[]>(`${this.API_BASE}/users/${userId}/audits`)
      .pipe(map((items) => items.map((item) => this.mapAuditItem(item))))
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
  revealDocument(
    documentId: number,
    request: DocumentAccessRequest,
  ): Observable<DocumentAccessGrant> {
    return this.http
      .post<DocumentAccessGrant>(`${this.API_BASE}/documents/${documentId}/reveal`, request)
      .pipe(shareReplay({ bufferSize: 1, refCount: true }));
  }

  downloadDocument(
    documentId: number,
    request: DocumentAccessRequest,
  ): Observable<DocumentAccessGrant> {
    return this.http
      .post<DocumentAccessGrant>(`${this.API_BASE}/documents/${documentId}/download`, request)
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
      .get<VerificationQueueStats>(`${this.API_BASE}/pending/stats`)
      .pipe(shareReplay({ bufferSize: 1, refCount: true }));
  }

  private mapPagedVerifications(page: BackendPagedRenterVerifications): PagedRenterVerifications {
    return {
      ...page,
      content: page.content.map((item) => ({
        ...item,
        riskLevel: item.riskLevel ?? 'LOW',
        documents: item.documents?.map((doc) => this.mapDocument(doc)),
      })),
    };
  }

  private mapVerificationDetails(
    profile: BackendAdminVerificationDetails,
    audits: BackendAuditTrailItem[],
  ): AdminVerificationDetails {
    const mappedDocuments = profile.documents.map((doc) => this.mapDocument(doc));
    const mappedAudits = audits.map((item) => this.mapAuditItem(item));
    const lastStatusChangeAt = mappedAudits[0]?.createdAt ?? profile.verifiedAt ?? profile.submittedAt;

    return {
      ...profile,
      documents: mappedDocuments,
      ocrResults: this.buildOcrResults(profile, mappedDocuments),
      auditTrail: mappedAudits,
      phoneNumber: null,
      registeredAt: null,
      lastStatusChangeAt,
    };
  }

  private mapDocument(doc: BackendRenterDocument): RenterDocument {
    const ocrConfidence = doc.ocrConfidencePercent != null ? doc.ocrConfidencePercent / 100 : null;
    const nameMatchScore = doc.nameMatchPercent != null ? doc.nameMatchPercent / 100 : null;
    const faceMatchScore = doc.faceMatchPercent != null ? doc.faceMatchPercent / 100 : null;

    return {
      id: doc.id,
      documentType: doc.type,
      verificationStatus: this.mapDocumentStatus(doc.status),
      uploadedAt: doc.uploadedAt,
      expiryDate: doc.expiryDate ?? null,
      rejectionReason: doc.rejectionReason ?? null,
      ocrConfidence,
      nameMatchScore,
      typeDisplay: doc.typeDisplay ?? null,
      filename: doc.filename ?? null,
      statusDisplay: doc.statusDisplay ?? null,
      processingStatus: doc.processingStatus ?? null,
      processingError: doc.processingError ?? null,
      ocrConfidencePercent: doc.ocrConfidencePercent ?? null,
      nameMatchPercent: doc.nameMatchPercent ?? null,
      faceMatchScore,
      faceMatchPercent: doc.faceMatchPercent ?? null,
      livenessPassed: doc.livenessPassed ?? null,
      ocrExtractedName: doc.ocrExtractedName ?? null,
      ocrExtractedNumber: doc.ocrExtractedNumber ?? null,
      ocrExtractedExpiry: doc.ocrExtractedExpiry ?? null,
    };
  }

  private mapAuditItem(item: BackendAuditTrailItem): VerificationAuditEvent {
    const action = item.action as VerificationAuditAction;

    return {
      id: item.id,
      eventType: action,
      action,
      previousStatus: item.previousStatus ?? null,
      newStatus: item.newStatus,
      actorName: item.actorName ?? null,
      actorEmail: item.actorName ?? null,
      reason: item.reason ?? null,
      notes: item.reason ?? null,
      metadata: null,
      createdAt: item.timestamp,
    };
  }

  private mapDocumentStatus(status: string): DocumentVerificationStatus {
    return status === 'EXPIRED_AUTO' ? 'EXPIRED' : (status as DocumentVerificationStatus);
  }

  private buildOcrResults(
    profile: BackendAdminVerificationDetails,
    documents: RenterDocument[],
  ): OcrResults | null {
    const licenseDocument = documents.find(
      (doc) => doc.documentType === 'DRIVERS_LICENSE_FRONT' || doc.documentType === 'DRIVERS_LICENSE_BACK',
    );
    const selfieDocument = documents.find((doc) => doc.documentType === 'SELFIE');

    if (!licenseDocument && !selfieDocument) {
      return null;
    }

    return {
      fullName: profile.fullName ?? null,
      licenseNumber: profile.maskedLicenseNumber ?? null,
      expiryDate: profile.licenseExpiryDate ?? null,
      categories: profile.licenseCategories
        ? profile.licenseCategories
            .split(',')
            .map((category) => category.trim())
            .filter(Boolean)
        : null,
      issuingCountry: profile.licenseCountry ?? null,
      extractedName: licenseDocument?.ocrExtractedName ?? null,
      extractedLicenseNumber: licenseDocument?.ocrExtractedNumber ?? null,
      documentExpiry: licenseDocument?.ocrExtractedExpiry ?? licenseDocument?.expiryDate ?? null,
      confidence: licenseDocument?.ocrConfidence ?? 0,
      nameMatchScore: licenseDocument?.nameMatchScore ?? undefined,
      faceMatchScore: selfieDocument?.faceMatchScore ?? null,
      livenessPassed: selfieDocument?.livenessPassed ?? null,
    };
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
  autoApprovedToday: number;
}

interface BackendRenterDocument {
  id: number;
  type: RenterDocument['documentType'];
  typeDisplay?: string | null;
  filename?: string | null;
  uploadedAt: string;
  expiryDate?: string | null;
  status: string;
  statusDisplay?: string | null;
  rejectionReason?: string | null;
  processingStatus?: string | null;
  processingError?: string | null;
  ocrConfidencePercent?: number | null;
  ocrExtractedName?: string | null;
  ocrExtractedNumber?: string | null;
  ocrExtractedExpiry?: string | null;
  livenessPassed?: boolean | null;
  faceMatchPercent?: number | null;
  nameMatchPercent?: number | null;
}

interface BackendRenterVerificationQueueItem {
  userId: number;
  userName: string;
  userEmail: string;
  submittedAt: string;
  riskLevel: VerificationQueueParams['riskLevel'];
  documentCount: number;
  documents?: BackendRenterDocument[];
}

interface BackendPagedRenterVerifications {
  content: BackendRenterVerificationQueueItem[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
}

interface BackendAdminVerificationDetails
  extends Omit<AdminVerificationDetails, 'documents' | 'ocrResults' | 'auditTrail' | 'lastStatusChangeAt'> {
  documents: BackendRenterDocument[];
}

interface BackendAuditTrailItem {
  id: number;
  action: string;
  previousStatus: VerificationAuditEvent['previousStatus'];
  newStatus: VerificationAuditEvent['newStatus'];
  reason?: string | null;
  actorName?: string | null;
  timestamp: string;
}
