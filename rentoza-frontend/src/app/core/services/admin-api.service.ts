import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';

import { User } from '../models/user.model';
import { HateoasPage, PaginatedResponse } from '../models/paginated-response.model';

// DTO Interfaces
// DTO Interfaces
export interface DashboardKpiDto {
  activeTripsCount: number;
  totalRevenueThisMonth: number;
  revenueGrowthPercent: number;
  pendingApprovalsCount: number;
  openDisputesCount: number;
  suspendedUsersCount: number;
  platformHealthScore: number;
  calculatedAt: string;
}

export interface AdminUserDto {
  id: number;
  email: string;
  firstName: string;
  lastName: string;
  role: string;
  banned: boolean;
  riskScore?: number;

  // Owner verification (Serbian compliance)
  ownerVerificationStatus?: 'NOT_SUBMITTED' | 'PENDING_REVIEW' | 'VERIFIED';
  ownerVerificationSubmittedAt?: string; // ISO string from backend
}

export interface AdminUserDetailDto extends AdminUserDto {
  phone: string;
  totalBookings: number;
  completedBookings: number;
  cancelledBookings: number;
  totalCars: number;
  riskFactors: string[];
  recentAdminActions: any[];

  // Owner verification review fields (masked only)
  ownerType?: 'INDIVIDUAL' | 'LEGAL_ENTITY';
  maskedJmbg?: string;
  maskedPib?: string;
  isIdentityVerified?: boolean;
  ownerVerificationSubmittedAt?: string;
  maskedBankAccountNumber?: string;
}

export interface OwnerVerificationRejectRequest {
  reason: string;
}

export interface BanUserRequest {
  reason: string;
}

export interface AdminCarDto {
  id: number;
  brand: string;
  model: string;
  year: number;
  ownerEmail: string;
  available: boolean;
  active: boolean; // Computed from available
  imageUrl?: string;

  // ✅ ADDED FOR APPROVAL WORKFLOW
  approvalStatus?: string;
  rejectionReason?: string;
  approvedAt?: string;
  approvedBy?: string;
  pricePerDay?: number;
  ownerName?: string;
}

export interface CarApprovalRequest {
  reason: string;
  notes?: string;
  approved: boolean;
}

/**
 * Detailed car review DTO for document verification workflow.
 */
export interface AdminCarReviewDetailDto {
  carId: number;
  brand: string;
  model: string;
  year: number;
  location: string;
  ownerName: string;
  ownerEmail: string;
  ownerIdentityVerified: boolean;
  ownerType: string; // INDIVIDUAL or LEGAL_ENTITY
  registrationExpiryDate: string; // LocalDate
  technicalInspectionDate: string; // LocalDate
  technicalInspectionExpiryDate: string; // LocalDate
  insuranceExpiryDate: string; // LocalDate
  documents: DocumentReviewDto[];
  imageUrls: string[]; // Base64 or URLs
  approvalState: ApprovalStateDto;
  createdAt: string;
}

/**
 * Single document in review workflow.
 */
export interface DocumentReviewDto {
  id: number;
  type: string; // REGISTRATION, TECHNICAL_INSPECTION, LIABILITY_INSURANCE, AUTHORIZATION
  status: string; // PENDING, VERIFIED, REJECTED, EXPIRED_AUTO
  uploadDate: string; // ISO datetime
  expiryDate: string; // ISO date
  isExpired: boolean;
  daysUntilExpiry: number;
  verifiedByName?: string;
  verifiedAt?: string;
  rejectionReason?: string;
  documentUrl: string;
  documentHash: string;
  originalFilename: string;
  mimeType: string;
  fileSize: number;
}

/**
 * Pre-calculated approval state for car.
 */
export interface ApprovalStateDto {
  ownerVerified: boolean;
  missingDocuments: string[];
  unverifiedDocuments: string[];
  expiredDocuments: string[];
  registrationValid: boolean;
  techInspectionValid: boolean;
  insuranceValid: boolean;
  canApprove: boolean;
}

/**
 * Request to verify or reject a document.
 */
export interface DocumentVerificationRequestDto {
  approved: boolean;
  rejectionReason?: string; // Required if approved=false, min 20 chars
}

// ==================== DISPUTES ====================

export interface AdminDisputeListDto {
  id: number;
  status: string;
  estimatedCostCents: number;
  guestName: string;
  hostName: string;
  createdAt: string;
}

export interface AdminDisputeDetailDto extends AdminDisputeListDto {
  bookingId: number;
  description: string;
  claimedAmount: number;
  approvedAmount?: number;
  checkinPhotoIds?: string;
  checkoutPhotoIds?: string;
  evidencePhotoIds?: string;
  guestResponse?: string;
  guestRespondedAt?: string;
  adminNotes?: string;
  reviewedBy?: string;
  reviewedAt?: string;
}

export interface DisputeResolutionRequest {
  decision: 'APPROVED' | 'REJECTED' | 'PARTIAL' | 'MEDIATED';
  approvedAmount?: number;
  notes: string;
  rejectionReason?: string;
}

export interface EscalateDisputeRequest {
  reason: string;
}

// ==================== FINANCIAL ====================

export interface PayoutQueueDto {
  bookingId: number;
  hostId: number;
  hostName: string;
  hostEmail: string;
  amountCents: number;
  currency: string;
  bookingCompletedAt: string;
  payoutScheduledFor: string;
  status: string;
  retryCount: number;
  failureReason?: string;
  paymentReference?: string;
}

export interface EscrowBalanceDto {
  totalEscrowBalance: number;
  pendingPayouts: number;
  availableBalance: number;
  frozenFunds: number;
  activeBookingsCount: number;
  pendingPayoutsCount: number;
  currency: string;
}

export interface BatchPayoutRequest {
  bookingIds: number[];
  dryRun?: boolean;
  notes: string;
}

export interface BatchPayoutResult {
  totalRequested: number;
  successCount: number;
  failureCount: number;
  totalAmountProcessed: number;
  failures: PayoutFailure[];
  batchReference: string;
}

export interface PayoutFailure {
  bookingId: number;
  reason: string;
  errorCode: string;
}

// ==================== ANALYTICS ====================

export interface RevenueTrendDto {
  dataPoints: RevenueDataPoint[];
  totalRevenue: number;
  averagePerPeriod: number;
  growthRate: number;
  period: string;
}

export interface RevenueDataPoint {
  date: string;
  revenue: number;
  bookingCount: number;
}

export interface CohortAnalysisDto {
  cohort: string;
  totalUsers: number;
  retentionByMonth: Record<number, RetentionMetrics>;
}

export interface RetentionMetrics {
  activeUsers: number;
  retentionRate: number;
  revenueGenerated: number;
  bookingCount: number;
}

export interface TopPerformersDto {
  topHosts: TopHost[];
  topCars: TopCar[];
}

export interface TopHost {
  hostId: number;
  hostName: string;
  bookingCount: number;
  totalRevenue: number;
  averageRating: number;
}

export interface TopCar {
  carId: number;
  carMake: string;
  carModel: string;
  bookingCount: number;
  totalRevenue: number;
  utilizationRate: number;
}

// ==================== AUDIT ====================

export interface AdminAuditLogDto {
  id: number;
  adminId: number;
  adminName: string;
  action: string;
  resourceType: string;
  resourceId: number;
  beforeState?: string;
  afterState?: string;
  notes?: string;
  timestamp: string;
  ipAddress?: string;
  userAgent?: string;
}

export interface AuditLogSearchParams {
  adminId?: number;
  resourceType?: string;
  resourceId?: number;
  action?: string;
  startDate?: string;
  endDate?: string;
  searchTerm?: string;
  page?: number;
  size?: number;
}

@Injectable({
  providedIn: 'root',
})
export class AdminApiService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.baseApiUrl}/admin`;

  // ==================== DASHBOARD ====================

  getDashboardKpis(): Observable<DashboardKpiDto> {
    return this.http.get<DashboardKpiDto>(`${this.apiUrl}/dashboard/kpis`);
  }

  // ==================== USER MANAGEMENT ====================

  getUsers(
    page: number = 0,
    size: number = 20,
    search?: string,
    pageUrl?: string,
    sort?: string
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

  // ==================== CAR MANAGEMENT ====================

  getPendingCars(): Observable<AdminCarDto[]> {
    return this.http.get<AdminCarDto[]>(`${this.apiUrl}/cars/pending`);
  }

  getCars(
    page: number = 0,
    size: number = 20,
    search?: string,
    pageUrl?: string
  ): Observable<PaginatedResponse<AdminCarDto>> {
    const url = pageUrl ?? `${this.apiUrl}/cars`;
    let options: { params?: HttpParams } = {};

    if (!pageUrl) {
      let params = new HttpParams().set('page', page.toString()).set('size', size.toString());

      if (search) {
        params = params.set('search', search);
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

  suspendCar(carId: number): Observable<AdminCarDto> {
    return this.http.post<AdminCarDto>(`${this.apiUrl}/cars/${carId}/suspend`, {});
  }

  reactivateCar(carId: number): Observable<AdminCarDto> {
    return this.http.post<AdminCarDto>(`${this.apiUrl}/cars/${carId}/reactivate`, {});
  }

  /**
   * Get car review details for document verification workflow.
   * Includes car photos, documents, and owner info.
   */
  getCarReviewDetail(carId: number): Observable<AdminCarReviewDetailDto> {
    return this.http.get<AdminCarReviewDetailDto>(`${this.apiUrl}/cars/${carId}/review-detail`);
  }

  /**
   * Verify or reject a document.
   * @param documentId Document ID
   * @param approved true to verify, false to reject
   * @param rejectionReason Reason if rejecting (min 20 chars)
   */
  verifyDocument(
    documentId: number,
    approved: boolean,
    rejectionReason?: string
  ): Observable<DocumentReviewDto> {
    return this.http.post<DocumentReviewDto>(`${this.apiUrl}/documents/${documentId}/verify`, {
      approved,
      rejectionReason,
    } as DocumentVerificationRequestDto);
  }

  // ==================== DISPUTE MANAGEMENT ====================

  listDisputes(
    page: number = 0,
    size: number = 20,
    status?: string
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

  escalateDispute(disputeId: number, request: EscalateDisputeRequest): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/disputes/${disputeId}/escalate`, request);
  }

  // ==================== FINANCIAL MANAGEMENT ====================

  getPayoutQueue(
    page: number = 0,
    size: number = 20
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
      {}
    );
  }

  // ==================== ANALYTICS ====================

  getRevenueTrend(
    period: 'DAILY' | 'WEEKLY' | 'MONTHLY',
    startDate: string,
    endDate: string
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
    searchParams: AuditLogSearchParams
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
    size: number = 20
  ): Observable<PaginatedResponse<AdminAuditLogDto>> {
    const params = new HttpParams().set('page', page.toString()).set('size', size.toString());

    return this.http
      .get<HateoasPage<AdminAuditLogDto>>(
        `${this.apiUrl}/audit/resource/${resourceType}/${resourceId}`,
        { params }
      )
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

  private normalizePage<T>(response: HateoasPage<T>): PaginatedResponse<T> {
    // Prefer HATEOAS embedded content; fall back to first embedded array or legacy Page content.
    let content: T[] = [];
    if (Array.isArray(response._embedded?.content)) {
      content = response._embedded!.content as T[];
    } else if (response._embedded) {
      const firstArray = Object.values(response._embedded).find((v) => Array.isArray(v)) as
        | T[]
        | undefined;
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
