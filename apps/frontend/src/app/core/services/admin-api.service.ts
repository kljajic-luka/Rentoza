import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';

import { User } from '../models/user.model';
import { HateoasPage, PaginatedResponse } from '../models/paginated-response.model';
import {
  RenterDocumentDto,
  RenterVerificationProfileDto,
  PagedVerificationResponse as PagedRenterVerificationResponse,
  VerificationQueueStats as RenterVerificationQueueStats,
  VerificationActionResponse as RenterVerificationActionResponse,
  VerificationAuditItem as RenterVerificationAuditItem,
  SignedUrlResponse,
} from '../models/admin-renter-verification.model';

// Re-export all DTO interfaces from the dedicated models file for backward compatibility
export type {
  DashboardKpiDto,
  RecentBookingDto,
  AdminUserDto,
  AdminUserDetailDto,
  OwnerVerificationRejectRequest,
  BanUserRequest,
  AdminCarDto,
  CarApprovalRequest,
  AdminCarReviewDetailDto,
  DocumentReviewDto,
  ApprovalStateDto,
  DocumentVerificationRequestDto,
  AdminDisputeListDto,
  AdminDisputeDetailDto,
  DisputeResolutionRequest,
  EscalateDisputeRequest,
  PayoutQueueDto,
  EscrowBalanceDto,
  BatchPayoutRequest,
  BatchPayoutResult,
  PayoutFailure,
  RevenueTrendDto,
  RevenueDataPoint,
  CohortAnalysisDto,
  RetentionMetrics,
  TopPerformersDto,
  TopHost,
  TopCar,
  AdminAuditLogDto,
  AuditLogSearchParams,
  AdminBookingDto,
  ForceCompleteBookingRequest,
  FlaggedMessageDto,
  FlaggedMessagePage,
  AdminSettings,
  ExpiringDocumentDto,
} from '../models/admin.models';

import type {
  DashboardKpiDto,
  RecentBookingDto,
  AdminUserDto,
  AdminUserDetailDto,
  OwnerVerificationRejectRequest,
  AdminCarDto,
  CarApprovalRequest,
  AdminCarReviewDetailDto,
  DocumentReviewDto,
  DocumentVerificationRequestDto,
  AdminDisputeListDto,
  AdminDisputeDetailDto,
  DisputeResolutionRequest,
  EscalateDisputeRequest,
  PayoutQueueDto,
  EscrowBalanceDto,
  BatchPayoutRequest,
  BatchPayoutResult,
  RevenueTrendDto,
  CohortAnalysisDto,
  TopPerformersDto,
  AdminAuditLogDto,
  AuditLogSearchParams,
  AdminBookingDto,
  ForceCompleteBookingRequest,
  FlaggedMessageDto,
  FlaggedMessagePage,
  AdminSettings,
  ExpiringDocumentDto,
} from '../models/admin.models';

@Injectable({
  providedIn: 'root',
})
export class AdminApiService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.baseApiUrl}/admin`;
  private chatApiUrl = `${environment.chatApiUrl}/admin`;

  // ==================== DASHBOARD ====================

  getDashboardKpis(): Observable<DashboardKpiDto> {
    return this.http.get<DashboardKpiDto>(`${this.apiUrl}/dashboard/kpis`);
  }

  /**
   * Get recent bookings for dashboard overview.
   * Returns the most recent bookings for quick admin reference.
   */
  getRecentBookings(limit: number = 5): Observable<RecentBookingDto[]> {
    const params = new HttpParams().set('limit', limit.toString());
    return this.http
      .get<RecentBookingDto[]>(`${this.apiUrl}/dashboard/recent-bookings`, { params })
      .pipe(
        // If endpoint doesn't exist yet, return empty array
        map((bookings) => bookings || []),
      );
  }

  // ==================== USER MANAGEMENT ====================

  getUsers(
    page: number = 0,
    size: number = 20,
    search?: string,
    pageUrl?: string,
    sort?: string,
  ): Observable<PaginatedResponse<AdminUserDto>> {
    const url = pageUrl ?? `${this.apiUrl}/users`;
    let options: { params?: HttpParams } = {};

    if (!pageUrl) {
      let params = new HttpParams().set('page', page.toString()).set('size', size.toString());

      if (search) {
        params = params.set('search', search);
      }

      if (sort) {
        params = params.append('sort', sort);
      }

      options = { params };
    }

    return this.http
      .get<HateoasPage<AdminUserDto>>(url, options)
      .pipe(map((response) => this.normalizePage(response)));
  }

  getUserDetail(userId: number): Observable<AdminUserDetailDto> {
    return this.http.get<AdminUserDetailDto>(`${this.apiUrl}/users/${userId}`);
  }

  deleteUser(userId: number, reason: string): Observable<any> {
    const params = new HttpParams().set('reason', reason);
    return this.http.delete(`${this.apiUrl}/users/${userId}`, { params });
  }

  banUser(userId: number, reason: string): Observable<any> {
    return this.http.put(`${this.apiUrl}/users/${userId}/ban`, { reason });
  }

  unbanUser(userId: number): Observable<any> {
    return this.http.put(`${this.apiUrl}/users/${userId}/unban`, {});
  }

  // ==================== OWNER VERIFICATION (ADMIN) ====================

  approveOwnerVerification(userId: number): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/owners/${userId}/approve`, {});
  }

  rejectOwnerVerification(userId: number, reason: string): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/owners/${userId}/reject`, {
      reason,
    } as OwnerVerificationRejectRequest);
  }

  // ==================== DOB CORRECTION (ADMIN) ====================

  approveDobCorrection(userId: number): Observable<{ message: string }> {
    return this.http.put<{ message: string }>(
      `${this.apiUrl}/users/${userId}/dob-correction/approve`,
      {},
    );
  }

  rejectDobCorrection(userId: number, reason?: string): Observable<{ message: string }> {
    let params = new HttpParams();
    if (reason) {
      params = params.set('reason', reason);
    }
    return this.http.put<{ message: string }>(
      `${this.apiUrl}/users/${userId}/dob-correction/reject`,
      {},
      { params },
    );
  }

  getPendingDobCorrections(
    page: number = 0,
    size: number = 20,
  ): Observable<PaginatedResponse<AdminUserDto>> {
    const params = new HttpParams().set('page', page.toString()).set('size', size.toString());
    return this.http
      .get<HateoasPage<AdminUserDto>>(`${this.apiUrl}/users/dob-corrections/pending`, { params })
      .pipe(map((response) => this.normalizePage(response)));
  }

  // ==================== RENTER VERIFICATION (ADMIN) ====================

  getRenterVerificationQueue(
    page: number = 0,
    size: number = 20,
    sortBy: 'newest' | 'oldest' | 'riskLevel' = 'newest',
  ): Observable<PagedRenterVerificationResponse> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString())
      .set('sortBy', sortBy);

    return this.http.get<PagedRenterVerificationResponse>(
      `${this.apiUrl}/renter-verifications/pending`,
      { params },
    );
  }

  getRenterVerificationStats(): Observable<RenterVerificationQueueStats> {
    return this.http.get<RenterVerificationQueueStats>(
      `${this.apiUrl}/renter-verifications/pending/stats`,
    );
  }

  getRenterVerificationDetails(userId: number): Observable<RenterVerificationProfileDto> {
    return this.http.get<RenterVerificationProfileDto>(
      `${this.apiUrl}/renter-verifications/users/${userId}`,
    );
  }

  getRenterDocument(documentId: number): Observable<RenterDocumentDto> {
    return this.http.get<RenterDocumentDto>(
      `${this.apiUrl}/renter-verifications/documents/${documentId}`,
    );
  }

  getRenterDocumentSignedUrl(
    documentId: number,
    reason: string,
    caseReference?: string,
  ): Observable<SignedUrlResponse> {
    return this.http.post<SignedUrlResponse>(
      `${this.apiUrl}/renter-verifications/documents/${documentId}/reveal`,
      { reason, caseReference },
    );
  }

  downloadRenterDocument(
    documentId: number,
    reason: string,
    caseReference?: string,
  ): Observable<SignedUrlResponse> {
    return this.http.post<SignedUrlResponse>(
      `${this.apiUrl}/renter-verifications/documents/${documentId}/download`,
      { reason, caseReference },
    );
  }

  approveRenterVerification(
    userId: number,
    notes?: string,
  ): Observable<RenterVerificationActionResponse> {
    return this.http.post<RenterVerificationActionResponse>(
      `${this.apiUrl}/renter-verifications/users/${userId}/approve`,
      { notes },
    );
  }

  rejectRenterVerification(
    userId: number,
    reason: string,
  ): Observable<RenterVerificationActionResponse> {
    return this.http.post<RenterVerificationActionResponse>(
      `${this.apiUrl}/renter-verifications/users/${userId}/reject`,
      { reason },
    );
  }

  suspendRenterVerification(
    userId: number,
    reason: string,
  ): Observable<RenterVerificationActionResponse> {
    return this.http.post<RenterVerificationActionResponse>(
      `${this.apiUrl}/renter-verifications/users/${userId}/suspend`,
      { reason },
    );
  }

  getRenterVerificationAudits(userId: number): Observable<RenterVerificationAuditItem[]> {
    return this.http.get<RenterVerificationAuditItem[]>(
      `${this.apiUrl}/renter-verifications/users/${userId}/audits`,
    );
  }

  retryRenterDocumentProcessing(
    documentId: number,
  ): Observable<{ success: boolean; message: string }> {
    return this.http.post<{ success: boolean; message: string }>(
      `${this.apiUrl}/renter-verifications/documents/${documentId}/retry-processing`,
      {},
    );
  }

  // ==================== CAR MANAGEMENT ====================

  getPendingCars(): Observable<AdminCarDto[]> {
    return this.http.get<AdminCarDto[]>(`${this.apiUrl}/cars/pending`);
  }

  getCars(
    page: number = 0,
    size: number = 20,
    search?: string,
    pageUrl?: string,
    status?: string,
    listedAfter?: string,
  ): Observable<PaginatedResponse<AdminCarDto>> {
    const url = pageUrl ?? `${this.apiUrl}/cars`;
    let options: { params?: HttpParams } = {};

    if (!pageUrl) {
      let params = new HttpParams().set('page', page.toString()).set('size', size.toString());

      if (search) {
        params = params.set('search', search);
      }

      if (status) {
        params = params.set('status', status);
      }

      if (listedAfter) {
        params = params.set('listedAfter', listedAfter);
      }

      options = { params };
    }

    return this.http
      .get<HateoasPage<AdminCarDto>>(url, options)
      .pipe(map((response) => this.normalizePage(response)));
  }

  getCarDetail(carId: number): Observable<AdminCarDto> {
    return this.http.get<AdminCarDto>(`${this.apiUrl}/cars/${carId}`);
  }

  approveCar(carId: number): Observable<AdminCarDto> {
    return this.http.post<AdminCarDto>(`${this.apiUrl}/cars/${carId}/approve`, {});
  }

  rejectCar(carId: number, reason: string): Observable<AdminCarDto> {
    return this.http.post<AdminCarDto>(`${this.apiUrl}/cars/${carId}/reject`, {
      approved: false,
      reason,
    } as CarApprovalRequest);
  }

  suspendCar(carId: number, reason: string): Observable<AdminCarDto> {
    return this.http.post<AdminCarDto>(`${this.apiUrl}/cars/${carId}/suspend`, { reason });
  }

  reactivateCar(carId: number): Observable<AdminCarDto> {
    return this.http.post<AdminCarDto>(`${this.apiUrl}/cars/${carId}/reactivate`, {});
  }

  getCarReviewDetail(carId: number): Observable<AdminCarReviewDetailDto> {
    return this.http.get<AdminCarReviewDetailDto>(`${this.apiUrl}/cars/${carId}/review-detail`);
  }

  verifyDocument(
    documentId: number,
    approved: boolean,
    rejectionReason?: string,
  ): Observable<DocumentReviewDto> {
    return this.http.post<DocumentReviewDto>(`${this.apiUrl}/documents/${documentId}/verify`, {
      approved,
      rejectionReason,
    } as DocumentVerificationRequestDto);
  }

  getExpiringDocuments(days: number = 30): Observable<ExpiringDocumentDto[]> {
    const params = new HttpParams().set('days', days.toString());
    return this.http.get<ExpiringDocumentDto[]>(`${this.apiUrl}/cars/expiring-documents`, {
      params,
    });
  }

  // ==================== DISPUTE MANAGEMENT ====================

  listDisputes(
    page: number = 0,
    size: number = 20,
    status?: string,
  ): Observable<PaginatedResponse<AdminDisputeListDto>> {
    let params = new HttpParams().set('page', page.toString()).set('size', size.toString());

    if (status) {
      params = params.set('status', status);
    }

    return this.http
      .get<HateoasPage<AdminDisputeListDto>>(`${this.apiUrl}/disputes`, { params })
      .pipe(map((response) => this.normalizePage(response)));
  }

  getDisputeDetail(disputeId: number): Observable<AdminDisputeDetailDto> {
    return this.http.get<AdminDisputeDetailDto>(`${this.apiUrl}/disputes/${disputeId}`);
  }

  resolveDispute(disputeId: number, request: DisputeResolutionRequest): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/disputes/${disputeId}/resolve`, request);
  }

  resolveCheckoutDispute(
    damageClaimId: number,
    request: {
      decision: 'APPROVE' | 'REJECT' | 'PARTIAL';
      approvedAmountRsd?: number;
      resolutionNotes: string;
      notifyParties?: boolean;
    },
  ): Observable<unknown> {
    return this.http.post(`${this.apiUrl}/disputes/checkout/${damageClaimId}/resolve`, request);
  }

  escalateDispute(disputeId: number, request: EscalateDisputeRequest): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/disputes/${disputeId}/escalate`, request);
  }

  // ==================== FINANCIAL MANAGEMENT ====================

  getPayoutQueue(
    page: number = 0,
    size: number = 20,
  ): Observable<PaginatedResponse<PayoutQueueDto>> {
    const params = new HttpParams().set('page', page.toString()).set('size', size.toString());

    return this.http
      .get<HateoasPage<PayoutQueueDto>>(`${this.apiUrl}/financial/payouts`, { params })
      .pipe(map((response) => this.normalizePage(response)));
  }

  getEscrowBalance(): Observable<EscrowBalanceDto> {
    return this.http.get<EscrowBalanceDto>(`${this.apiUrl}/financial/escrow`);
  }

  processBatchPayouts(request: BatchPayoutRequest): Observable<BatchPayoutResult> {
    return this.http.post<BatchPayoutResult>(`${this.apiUrl}/financial/payouts/batch`, request);
  }

  retryPayout(bookingId: number): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(
      `${this.apiUrl}/financial/payouts/${bookingId}/retry`,
      {},
    );
  }

  // ==================== ANALYTICS ====================

  getRevenueTrend(
    period: 'DAILY' | 'WEEKLY' | 'MONTHLY',
    startDate: string,
    endDate: string,
  ): Observable<RevenueTrendDto> {
    const params = new HttpParams()
      .set('period', period)
      .set('startDate', startDate)
      .set('endDate', endDate);

    return this.http.get<RevenueTrendDto>(`${this.apiUrl}/analytics/revenue-trend`, { params });
  }

  getCohortAnalysis(cohort: string, monthsToTrack: number = 6): Observable<CohortAnalysisDto> {
    const params = new HttpParams()
      .set('cohort', cohort)
      .set('monthsToTrack', monthsToTrack.toString());

    return this.http.get<CohortAnalysisDto>(`${this.apiUrl}/analytics/cohort`, { params });
  }

  getTopPerformers(topN: number, startDate: string, endDate: string): Observable<TopPerformersDto> {
    const params = new HttpParams()
      .set('topN', topN.toString())
      .set('startDate', startDate)
      .set('endDate', endDate);

    return this.http.get<TopPerformersDto>(`${this.apiUrl}/analytics/top-performers`, { params });
  }

  // ==================== AUDIT LOGS ====================

  searchAuditLogs(
    searchParams: AuditLogSearchParams,
  ): Observable<PaginatedResponse<AdminAuditLogDto>> {
    let params = new HttpParams()
      .set('page', (searchParams.page ?? 0).toString())
      .set('size', (searchParams.size ?? 20).toString());

    if (searchParams.adminId) params = params.set('adminId', searchParams.adminId.toString());
    if (searchParams.resourceType) params = params.set('resourceType', searchParams.resourceType);
    if (searchParams.resourceId)
      params = params.set('resourceId', searchParams.resourceId.toString());
    if (searchParams.action) params = params.set('action', searchParams.action);
    if (searchParams.startDate) params = params.set('startDate', searchParams.startDate);
    if (searchParams.endDate) params = params.set('endDate', searchParams.endDate);
    if (searchParams.searchTerm) params = params.set('searchTerm', searchParams.searchTerm);

    return this.http
      .get<HateoasPage<AdminAuditLogDto>>(`${this.apiUrl}/audit`, { params })
      .pipe(map((response) => this.normalizePage(response)));
  }

  getAuditLogById(id: number): Observable<AdminAuditLogDto> {
    return this.http.get<AdminAuditLogDto>(`${this.apiUrl}/audit/${id}`);
  }

  getAuditLogsForResource(
    resourceType: string,
    resourceId: number,
    page: number = 0,
    size: number = 20,
  ): Observable<PaginatedResponse<AdminAuditLogDto>> {
    const params = new HttpParams().set('page', page.toString()).set('size', size.toString());

    return this.http
      .get<
        HateoasPage<AdminAuditLogDto>
      >(`${this.apiUrl}/audit/resource/${resourceType}/${resourceId}`, { params })
      .pipe(map((response) => this.normalizePage(response)));
  }

  exportAuditLogs(searchParams: AuditLogSearchParams): Observable<Blob> {
    let params = new HttpParams();

    if (searchParams.adminId) params = params.set('adminId', searchParams.adminId.toString());
    if (searchParams.resourceType) params = params.set('resourceType', searchParams.resourceType);
    if (searchParams.resourceId)
      params = params.set('resourceId', searchParams.resourceId.toString());
    if (searchParams.action) params = params.set('action', searchParams.action);
    if (searchParams.startDate) params = params.set('startDate', searchParams.startDate);
    if (searchParams.endDate) params = params.set('endDate', searchParams.endDate);
    if (searchParams.searchTerm) params = params.set('searchTerm', searchParams.searchTerm);

    return this.http.get(`${this.apiUrl}/audit/export`, {
      params,
      responseType: 'blob',
    });
  }

  getAuditStats(startDate?: string, endDate?: string): Observable<any> {
    let params = new HttpParams();
    if (startDate) params = params.set('startDate', startDate);
    if (endDate) params = params.set('endDate', endDate);

    return this.http.get(`${this.apiUrl}/audit/stats`, { params });
  }

  // ==================== ADMIN SETTINGS ====================

  getAdminSettings(): Observable<AdminSettings> {
    return this.http.get<AdminSettings>(`${this.apiUrl}/settings`);
  }

  updateAdminSettings(settings: AdminSettings): Observable<AdminSettings> {
    return this.http.put<AdminSettings>(`${this.apiUrl}/settings`, settings);
  }

  resetAdminSettings(): Observable<AdminSettings> {
    return this.http.post<AdminSettings>(`${this.apiUrl}/settings/reset`, {});
  }

  // ==================== BOOKINGS ====================

  getBookings(params?: {
    status?: string;
    search?: string;
    page?: number;
    size?: number;
  }): Observable<PaginatedResponse<AdminBookingDto>> {
    let httpParams = new HttpParams();
    if (params?.status) httpParams = httpParams.set('status', params.status);
    if (params?.search) httpParams = httpParams.set('search', params.search);
    if (params?.page !== undefined) httpParams = httpParams.set('page', params.page.toString());
    if (params?.size !== undefined) httpParams = httpParams.set('size', params.size.toString());
    return this.http
      .get<any>(`${this.apiUrl}/bookings`, { params: httpParams })
      .pipe(map((response) => this.normalizePage<AdminBookingDto>(response)));
  }

  getBookingDetail(id: number): Observable<AdminBookingDto> {
    return this.http.get<AdminBookingDto>(`${this.apiUrl}/bookings/${id}`);
  }

  forceCompleteBooking(id: number, reason: string): Observable<AdminBookingDto> {
    return this.http.post<AdminBookingDto>(`${this.apiUrl}/bookings/${id}/force-complete`, {
      reason,
    } as ForceCompleteBookingRequest);
  }

  // ==================== FLAGGED MESSAGE MODERATION ====================

  getFlaggedMessages(page: number = 0, size: number = 20): Observable<FlaggedMessagePage> {
    return this.http.get<FlaggedMessagePage>(`${this.chatApiUrl}/messages/flagged`, {
      params: { page: page.toString(), size: size.toString() },
    });
  }

  getFlaggedMessageCount(): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${this.chatApiUrl}/messages/flagged/count`);
  }

  dismissMessageFlags(messageId: number): Observable<void> {
    return this.http.post<void>(`${this.chatApiUrl}/messages/${messageId}/dismiss-flags`, {});
  }

  private normalizePage<T>(response: HateoasPage<T>): PaginatedResponse<T> {
    let content: T[] = [];
    if (Array.isArray(response._embedded?.content)) {
      content = response._embedded.content;
    } else if (response._embedded) {
      const firstArray = Object.values(response._embedded).find((v) => Array.isArray(v));
      if (firstArray) {
        content = firstArray;
      }
    }
    if (!content.length && Array.isArray((response as any).content)) {
      content = (response as any).content as T[];
    }

    const page = response.page ??
      (response as any).pageable ?? {
        size: (response as any).size,
        totalElements: (response as any).totalElements,
        totalPages: (response as any).totalPages,
        number: (response as any).number,
      };

    return {
      content,
      totalElements: page?.totalElements ?? (response as any).totalElements ?? content.length,
      page: page?.number ?? (response as any).number ?? 0,
      size: page?.size ?? (response as any).size ?? content.length,
      links: response._links,
    };
  }
}
