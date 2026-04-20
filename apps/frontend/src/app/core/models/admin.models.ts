// ==================== DASHBOARD ====================

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

export interface RecentBookingDto {
  id: number;
  carTitle: string;
  renterName: string;
  ownerName: string;
  status: string;
  startDate: string;
  endDate: string;
  totalPrice: number;
  createdAt: string;
}

// ==================== USER MANAGEMENT ====================

export interface AdminUserDto {
  id: number;
  email: string;
  firstName: string;
  lastName: string;
  role: string;
  banned: boolean;
  riskScore?: number;
  ownerVerificationStatus?: 'NOT_SUBMITTED' | 'PENDING_REVIEW' | 'VERIFIED' | 'REJECTED';
  ownerVerificationSubmittedAt?: string;
  dobCorrectionStatus?: 'PENDING' | 'APPROVED' | 'REJECTED';
}

export interface AdminUserDetailDto extends AdminUserDto {
  phone: string;
  totalBookings: number;
  completedBookings: number;
  cancelledBookings: number;
  disputedBookings?: number;
  totalCars: number;
  activeCars?: number;
  riskFactors: string[];
  recentAdminActions: AdminAction[];
  createdAt: string;
  updatedAt?: string;
  ownerType?: 'INDIVIDUAL' | 'LEGAL_ENTITY';
  maskedJmbg?: string;
  maskedPib?: string;
  isIdentityVerified?: boolean;
  maskedBankAccountNumber?: string;
  banReason?: string;
  bannedAt?: string;
  bannedByName?: string;
  reviewsGiven?: number;
  reviewsReceived?: number;
  averageRating?: number;

  // Identity rejection (M-3)
  identityRejectionReason?: string;
  identityRejectedAt?: string;

  // DOB correction (M-9)
  dobCorrectionRequestedValue?: string;
  dobCorrectionRequestedAt?: string;
  dobCorrectionReason?: string;
  dobCorrectionStatus?: 'PENDING' | 'APPROVED' | 'REJECTED';
}

export interface AdminAction {
  id: number;
  action: string;
  performedBy: string;
  performedAt: string;
  details?: string;
}

export interface OwnerVerificationRejectRequest {
  reason: string;
}

export interface BanUserRequest {
  reason: string;
}

// ==================== CAR MANAGEMENT ====================

export interface AdminCarDto {
  id: number;
  brand: string;
  model: string;
  year: number;
  ownerEmail: string;
  available: boolean;
  active: boolean;
  imageUrl?: string;
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

export interface AdminCarReviewDetailDto {
  carId: number;
  brand: string;
  model: string;
  year: number;
  location: string;
  ownerName: string;
  ownerEmail: string;
  ownerIdentityVerified: boolean;
  ownerType: string;
  registrationExpiryDate: string;
  technicalInspectionDate: string;
  technicalInspectionExpiryDate: string;
  insuranceExpiryDate: string;
  documents: DocumentReviewDto[];
  imageUrls: string[];
  approvalState: ApprovalStateDto;
  createdAt: string;
}

export interface DocumentReviewDto {
  id: number;
  type: string;
  status: string;
  uploadDate: string;
  expiryDate: string;
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

export interface DocumentVerificationRequestDto {
  approved: boolean;
  rejectionReason?: string;
}

export interface ExpiringDocumentDto {
  carId: number;
  carBrand: string;
  carModel: string;
  carYear: number;
  ownerName: string;
  ownerEmail: string;
  documentType: string;
  expiryDate: string;
  daysRemaining: number;
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
  disputeStage?: string;
  disputeType?: string;
  initiator?: string;
  adminReviewRequired?: boolean;
  repairQuoteDocumentUrl?: string;
  guestId?: number;
  guestEmail?: string;
  guestPhone?: string;
  hostId?: number;
  hostEmail?: string;
  hostPhone?: string;
  carId?: number;
}

export interface DisputeResolutionRequest {
  decision: 'APPROVED' | 'REJECTED' | 'PARTIAL' | 'MEDIATED';
  approvedAmount?: number;
  notes: string;
  rejectionReason?: string;
}

export interface DisputeResolutionResult {
  resolved: boolean;
  paymentSucceeded: boolean;
  notificationsSent: boolean;
  manualReviewRequired: boolean;
  claimStatus: string;
  message?: string;
  paymentError?: string;
}

export interface CheckoutDisputeResolutionResult {
  bookingId: number;
  damageClaimId: number;
  decision: string;
  originalClaimAmountRsd: number;
  approvedAmountRsd: number;
  depositReleasedRsd: number;
  depositCapturedRsd: number;
  resolutionNotes: string;
  resolvedByAdminId: number;
  resolvedByAdminName: string;
  resolvedAt: string;
  sagaResumed: boolean;
  newBookingStatus: string;
  notificationsSent: boolean;
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
  queuedCount: number;
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

// ==================== ADMIN BOOKINGS ====================

export interface AdminBookingDto {
  id: number;
  status: string;
  paymentStatus: string;
  carId: number;
  carTitle: string;
  renterId: number;
  renterName: string;
  renterEmail: string;
  ownerId: number;
  ownerName: string;
  ownerEmail: string;
  startTime: string;
  endTime: string;
  totalPrice: number;
  insuranceType: string;
  createdAt: string;
  chargeLifecycleStatus?: string;
  captureAttempts?: number;
}

export interface ForceCompleteBookingRequest {
  reason: string;
}

// ==================== FLAGGED MESSAGES ====================

export interface FlaggedMessageDto {
  id: number;
  senderId: number;
  conversationId: number;
  content: string;
  moderationFlags: string;
  timestamp: string;
  mediaUrl?: string;
}

export interface FlaggedMessagePage {
  content: FlaggedMessageDto[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// ==================== ADMIN SETTINGS ====================

export interface AdminSettings {
  emailNotifications: boolean;
  pushNotifications: boolean;
  smsNotifications: boolean;
  weeklyReport: boolean;
  monthlyReport: boolean;
  reportFormat: 'pdf' | 'csv' | 'xlsx';
  timezone: string;
  currencyFormat: string;
  loginAlerts: boolean;
}
