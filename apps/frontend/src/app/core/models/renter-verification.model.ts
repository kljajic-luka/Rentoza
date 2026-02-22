/**
 * Renter Driver License Verification Models
 *
 * Enterprise-grade type definitions for the renter identity verification feature.
 * Mirrors backend DTOs while providing frontend-specific utilities.
 *
 * @see RenterVerificationService for API integration
 * @see RenterVerificationPageComponent for UI implementation
 */

// ============================================================================
// ENUMS
// ============================================================================

/**
 * Driver license verification status.
 * Mirrors backend DriverLicenseStatus enum.
 */
export type DriverLicenseStatus =
  | 'NOT_STARTED'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'SUSPENDED';

/**
 * Risk assessment level for verification.
 * Determines auto-approval eligibility.
 */
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

/**
 * Document types for renter verification.
 * Must match backend RenterDocumentType enum values.
 */
export type RenterDocumentType =
  | 'DRIVERS_LICENSE_FRONT'
  | 'DRIVERS_LICENSE_BACK'
  | 'SELFIE'
  | 'ID_CARD_FRONT'
  | 'ID_CARD_BACK'
  | 'PASSPORT';

/**
 * Document verification status.
 */
export type DocumentVerificationStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'VERIFIED'
  | 'REJECTED'
  | 'EXPIRED';

// ============================================================================
// RESPONSE DTOs
// ============================================================================

/**
 * Complete renter verification profile from backend.
 * Contains all information about a user's verification status.
 *
 * NOTE: Field names must match backend RenterVerificationProfileDTO exactly.
 */
export interface RenterVerificationProfile {
  userId: number;
  fullName: string;
  email: string;

  // License verification status
  status: DriverLicenseStatus;
  statusDisplay: string;
  canBook: boolean;
  bookingBlockedReason: string | null;

  // License details
  maskedLicenseNumber: string | null; // "ABC****56"
  licenseExpiryDate: string | null; // ISO date (YYYY-MM-DD)
  daysUntilExpiry: number | null;
  expiryWarning: boolean;
  licenseCountry: string | null;
  licenseCategories: string | null;
  licenseTenureMonths: number | null;

  // Verification timestamps
  submittedAt: string | null; // ISO datetime
  verifiedAt: string | null; // ISO datetime
  verifiedByName: string | null;

  // Risk assessment
  riskLevel: RiskLevel | null;
  riskLevelDisplay: string | null;

  // Submitted documents
  documents: RenterDocument[];

  // Document completeness
  requiredDocumentsComplete: boolean;
  missingDocuments: string[];
  canSubmit: boolean;

  // UX hints
  estimatedWaitTime: string | null;
  rejectionReason: string | null;
  nextSteps: string | null;
}

/**
 * Individual document in verification process.
 */
export interface RenterDocument {
  id: number;
  documentType: RenterDocumentType;
  verificationStatus: DocumentVerificationStatus;
  uploadedAt: string; // ISO datetime
  expiryDate: string | null; // ISO date
  rejectionReason: string | null;
  ocrConfidence: number | null; // 0.0-1.0
  nameMatchScore: number | null; // 0.0-1.0
}

/**
 * Booking eligibility check result.
 */
export interface BookingEligibility {
  eligible: boolean;
  blockedReason: string | null;
  messageSr: string | null; // Serbian user-facing message
  messageEn: string | null; // English user-facing message
  licenseExpiresBeforeTripEnd: boolean;
  daysUntilExpiry: number | null;
}

// ============================================================================
// REQUEST DTOs
// ============================================================================

/**
 * Request to submit driver license for verification.
 * Files are sent via FormData (multipart/form-data).
 */
export interface DriverLicenseSubmissionRequest {
  /** Front side of driver's license (required) */
  licenseFront: File;
  /** Back side of driver's license (required) */
  licenseBack: File;
  /** Selfie for liveness/face-match checks (required when selfie verification is enabled) */
  selfie?: File;
}

// ============================================================================
// ADMIN DTOs
// ============================================================================

/**
 * Paginated response for admin verification queue.
 */
export interface PagedRenterVerifications {
  content: RenterVerificationQueueItem[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
}

/**
 * Single item in admin verification queue.
 * Field names match backend AdminRenterVerificationController.PendingVerificationItem
 */
export interface RenterVerificationQueueItem {
  userId: number;
  userName: string;
  userEmail: string;
  submittedAt: string; // ISO datetime
  riskLevel: RiskLevel;
  documentCount: number;
  documents?: RenterDocument[];
}

/**
 * Full verification details for admin review.
 */
export interface AdminVerificationDetails extends RenterVerificationProfile {
  ocrResults: OcrResults | null;
  auditTrail: VerificationAuditEvent[];
  phoneNumber?: string | null;
  registeredAt?: string | null;
  lastStatusChangeAt?: string | null;
}

/**
 * OCR extraction results for admin review.
 */
export interface OcrResults {
  fullName?: string | null;
  licenseNumber?: string | null;
  dateOfBirth?: string | null; // ISO date
  issueDate?: string | null; // ISO date
  expiryDate?: string | null; // ISO date
  categories?: string[] | null;
  issuingCountry?: string | null;
  extractedName?: string | null;
  extractedDob?: string | null; // ISO date
  extractedLicenseNumber?: string | null;
  documentExpiry?: string | null; // ISO date
  confidence: number; // 0.0-1.0
  nameMatchScore?: number; // 0.0-1.0
  faceMatchScore?: number | null; // 0.0-1.0
  livenessPassed?: boolean | null;
}

/**
 * Audit trail entry for verification actions.
 */
export interface VerificationAuditEvent {
  id: number;
  eventType: VerificationAuditAction;
  action?: VerificationAuditAction; // alias for eventType
  previousStatus: DriverLicenseStatus | null;
  newStatus: DriverLicenseStatus;
  actorName?: string | null; // Admin name or 'SYSTEM'
  actorEmail?: string | null;
  reason?: string | null;
  notes?: string | null;
  metadata?: Record<string, unknown> | null;
  createdAt: string; // ISO datetime
}

export type VerificationAuditAction =
  | 'DOCUMENT_SUBMITTED'
  | 'AUTO_APPROVED'
  | 'AUTO_REJECTED'
  | 'MANUAL_APPROVED'
  | 'MANUAL_REJECTED'
  | 'SUSPENDED'
  | 'RESUBMISSION_REQUESTED'
  | 'EXPIRED'
  | 'REACTIVATED';

// ============================================================================
// ADMIN REQUEST DTOs
// ============================================================================

/**
 * Admin queue filter parameters.
 */
export interface VerificationQueueParams {
  page?: number;
  size?: number;
  status?: DriverLicenseStatus;
  riskLevel?: RiskLevel;
  sortBy?: 'newest' | 'oldest' | 'riskLevel';
}

/**
 * Admin approve request.
 */
export interface ApproveVerificationRequest {
  notes?: string;
}

/**
 * Admin reject request.
 */
export interface RejectVerificationRequest {
  reason: string;
}

/**
 * Admin suspend request.
 */
export interface SuspendVerificationRequest {
  reason: string;
}

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================

/**
 * Check if a verification status means the user is approved to book.
 */
export function isApproved(status: DriverLicenseStatus): boolean {
  return status === 'APPROVED';
}

/**
 * Check if a verification status is terminal (not pending).
 */
export function isTerminal(status: DriverLicenseStatus): boolean {
  return !['NOT_STARTED', 'PENDING_REVIEW'].includes(status);
}

/**
 * Check if a verification status allows document re-submission.
 * Used for UI text ("re-submit" vs "submit").
 *
 * NOT_STARTED: Initial submission (not a resubmit)
 * REJECTED: Re-submit after admin rejection
 * EXPIRED: Re-submit after license expiry
 */
export function canResubmit(status: DriverLicenseStatus): boolean {
  return ['REJECTED', 'EXPIRED'].includes(status);
}

/**
 * Check if a verification status requires document upload.
 *
 * NOT_STARTED: Need to submit documents
 * REJECTED: Need to re-submit after rejection
 * EXPIRED: Need to re-submit after expiry
 *
 * NOTE: PENDING_REVIEW is NOT included - use requiresAdditionalDocuments() instead
 * to check if incomplete documents need to be uploaded during pending review.
 */
export function requiresDocumentUpload(status: DriverLicenseStatus): boolean {
  return ['NOT_STARTED', 'REJECTED', 'EXPIRED'].includes(status);
}

/**
 * Check if user in PENDING_REVIEW needs to upload additional documents.
 * Used when status is PENDING_REVIEW but required documents are incomplete.
 */
export function requiresAdditionalDocuments(
  status: DriverLicenseStatus,
  requiredDocumentsComplete: boolean
): boolean {
  return status === 'PENDING_REVIEW' && !requiredDocumentsComplete;
}

/**
 * Check if upload form should be shown to the user.
 * Combines status-based requirements with document completeness.
 */
export function shouldShowUploadForm(
  status: DriverLicenseStatus,
  requiredDocumentsComplete: boolean
): boolean {
  // Always show for initial/resubmit states
  if (requiresDocumentUpload(status)) {
    return true;
  }
  // Show during pending if documents incomplete
  if (requiresAdditionalDocuments(status, requiredDocumentsComplete)) {
    return true;
  }
  return false;
}

/**
 * Check if a verification status blocks all activity.
 */
export function isBlocked(status: DriverLicenseStatus): boolean {
  return status === 'SUSPENDED';
}

/**
 * Get user-friendly status label (Serbian).
 */
export function getStatusLabelSr(status: DriverLicenseStatus): string {
  const labels: Record<DriverLicenseStatus, string> = {
    NOT_STARTED: 'Nije započeto',
    PENDING_REVIEW: 'Na pregledu',
    APPROVED: 'Verifikovano',
    REJECTED: 'Odbijeno',
    EXPIRED: 'Isteklo',
    SUSPENDED: 'Suspendovano',
  };
  return labels[status];
}

/**
 * Get user-friendly status label (English).
 */
export function getStatusLabelEn(status: DriverLicenseStatus): string {
  const labels: Record<DriverLicenseStatus, string> = {
    NOT_STARTED: 'Not Started',
    PENDING_REVIEW: 'Under Review',
    APPROVED: 'Verified',
    REJECTED: 'Rejected',
    EXPIRED: 'Expired',
    SUSPENDED: 'Suspended',
  };
  return labels[status];
}

/**
 * Get CSS class for status badge styling.
 */
export function getStatusClass(status: DriverLicenseStatus): string {
  const classes: Record<DriverLicenseStatus, string> = {
    NOT_STARTED: 'status-default',
    PENDING_REVIEW: 'status-pending',
    APPROVED: 'status-success',
    REJECTED: 'status-error',
    EXPIRED: 'status-error',
    SUSPENDED: 'status-error',
  };
  return classes[status];
}

/**
 * Get icon name for status.
 */
export function getStatusIcon(status: DriverLicenseStatus): string {
  const icons: Record<DriverLicenseStatus, string> = {
    NOT_STARTED: 'help_outline',
    PENDING_REVIEW: 'hourglass_empty',
    APPROVED: 'verified',
    REJECTED: 'cancel',
    EXPIRED: 'schedule',
    SUSPENDED: 'block',
  };
  return icons[status];
}

/**
 * Get risk level badge class.
 */
export function getRiskLevelClass(level: RiskLevel): string {
  const classes: Record<RiskLevel, string> = {
    LOW: 'risk-low',
    MEDIUM: 'risk-medium',
    HIGH: 'risk-high',
  };
  return classes[level];
}
