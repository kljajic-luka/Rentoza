/**
 * Renter Verification Admin Models
 * 
 * TypeScript interfaces matching backend DTOs:
 * - RenterDocumentDTO.java
 * - RenterVerificationProfileDTO.java
 * - AdminRenterVerificationController.java response DTOs
 * 
 * @see AdminRenterVerificationController.java
 */

// ==================== ENUMS ====================

/**
 * Document types for renter verification.
 * Matches RenterDocumentType.java
 */
export type RenterDocumentType = 
  | 'DRIVERS_LICENSE_FRONT'
  | 'DRIVERS_LICENSE_BACK'
  | 'SELFIE';

/**
 * Driver license verification status.
 * Matches DriverLicenseStatus.java
 */
export type DriverLicenseStatus = 
  | 'NOT_SUBMITTED'
  | 'PENDING_DOCUMENTS'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'SUSPENDED';

/**
 * Document verification status.
 * Matches DocumentVerificationStatus.java
 */
export type DocumentVerificationStatus = 
  | 'PENDING'
  | 'VERIFIED'
  | 'REJECTED'
  | 'EXPIRED_AUTO';

/**
 * Document processing status.
 * Matches RenterDocument.ProcessingStatus
 */
export type DocumentProcessingStatus = 
  | 'PENDING'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'FAILED';

/**
 * User risk level.
 * Matches RiskLevel.java
 */
export type RiskLevel = 
  | 'LOW'
  | 'MEDIUM'
  | 'HIGH'
  | 'CRITICAL';

// ==================== DOCUMENT DTO ====================

/**
 * Renter document with OCR/biometric data.
 * Matches RenterDocumentDTO.java
 */
export interface RenterDocumentDto {
  id: number;
  type: RenterDocumentType;
  typeDisplay: string;  // Serbian name
  filename?: string;
  uploadedAt: string;   // ISO datetime
  expiryDate?: string;  // ISO date
  expired: boolean;
  status: DocumentVerificationStatus;
  statusDisplay: string;
  rejectionReason?: string;
  
  // Processing info
  processingStatus: DocumentProcessingStatus;
  processingError?: string;
  
  // OCR results (admin view)
  ocrConfidencePercent?: number;    // 0-100
  ocrExtractedName?: string;
  ocrExtractedNumber?: string;
  ocrExtractedExpiry?: string;      // ISO date
  
  // Biometric results (admin view)
  livenessPassed?: boolean;
  faceMatchPercent?: number;        // 0-100
  nameMatchPercent?: number;        // 0-100
  
  // Admin view
  downloadUrl?: string;             // Signed URL
  userId?: number;
  userName?: string;
}

// ==================== VERIFICATION PROFILE ====================

/**
 * Complete renter verification profile for admin review.
 * Matches RenterVerificationProfileDTO.java
 */
export interface RenterVerificationProfileDto {
  // User info
  userId: number;
  fullName: string;
  email: string;
  
  // License status
  status: DriverLicenseStatus;
  statusDisplay: string;
  canBook: boolean;
  bookingBlockedReason?: string;
  
  // License details
  maskedLicenseNumber?: string;
  licenseExpiryDate?: string;       // ISO date
  daysUntilExpiry?: number;
  expiryWarning: boolean;
  licenseCountry?: string;
  licenseCategories?: string;
  licenseTenureMonths?: number;
  
  // Verification timestamps
  submittedAt?: string;             // ISO datetime
  verifiedAt?: string;              // ISO datetime
  verifiedByName?: string;
  
  // Risk & scoring
  riskLevel?: RiskLevel;
  riskLevelDisplay?: string;
  
  // Documents
  documents: RenterDocumentDto[];
  requiredDocumentsComplete: boolean;
  missingDocuments?: string[];
  
  // UI helpers
  estimatedWaitTime?: string;
  canSubmit: boolean;
  rejectionReason?: string;
  nextSteps?: string;
}

// ==================== QUEUE ITEMS ====================

/**
 * Item in the pending verification queue.
 * Matches AdminRenterVerificationController.PendingVerificationItem
 */
export interface PendingVerificationItem {
  userId: number;
  userName: string;
  userEmail: string;
  submittedAt: string;              // ISO datetime
  riskLevel?: RiskLevel;
  documentCount: number;
  documents: RenterDocumentDto[];
}

/**
 * Paginated response for verification queue.
 * Matches AdminRenterVerificationController.PagedVerificationResponse
 */
export interface PagedVerificationResponse {
  content: PendingVerificationItem[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
}

/**
 * Queue statistics.
 * Matches AdminRenterVerificationController.QueueStats
 */
export interface VerificationQueueStats {
  pendingCount: number;
  approvedToday: number;
  rejectedToday: number;
  autoApprovedToday: number;
}

// ==================== ACTION REQUESTS ====================

/**
 * Request to approve verification.
 * Matches AdminRenterVerificationController.ApprovalRequest
 */
export interface ApproveRenterVerificationRequest {
  notes?: string;
}

/**
 * Request to reject verification.
 * Matches AdminRenterVerificationController.RejectionRequest
 */
export interface RejectRenterVerificationRequest {
  reason: string;
}

/**
 * Request to suspend user.
 * Matches AdminRenterVerificationController.SuspensionRequest
 */
export interface SuspendRenterVerificationRequest {
  reason: string;
}

// ==================== ACTION RESPONSES ====================

/**
 * Response from approve/reject/suspend actions.
 * Matches AdminRenterVerificationController.ApprovalResponse
 */
export interface VerificationActionResponse {
  userId: number;
  newStatus: DriverLicenseStatus;
  message: string;
}

/**
 * Signed URL response for document viewing.
 * Matches AdminRenterVerificationController.SignedUrlResponse
 */
export interface SignedUrlResponse {
  url: string;
  expiresAt: string;                // ISO datetime
  documentId: number;
}

// ==================== AUDIT ====================

/**
 * Verification audit history item.
 * Matches AdminRenterVerificationController.AuditHistoryItem
 */
export interface VerificationAuditItem {
  id: number;
  action: string;
  previousStatus?: DriverLicenseStatus;
  newStatus?: DriverLicenseStatus;
  reason?: string;
  actorName: string;
  timestamp: string;                // ISO datetime
}

// ==================== UI HELPERS ====================

/**
 * Common rejection reasons for quick selection.
 */
export const REJECTION_REASONS = [
  { value: 'blurry', label: 'Mutna ili nekvalitetna slika', labelEn: 'Blurry/low quality image' },
  { value: 'face_mismatch', label: 'Lice ne odgovara fotografiji na vozačkoj dozvoli', labelEn: 'Face doesn\'t match license' },
  { value: 'license_expired', label: 'Vozačka dozvola je istekla', labelEn: 'License expired' },
  { value: 'not_visible', label: 'Vozačka dozvola nije jasno vidljiva', labelEn: 'License not clearly visible' },
  { value: 'info_mismatch', label: 'Podaci ne odgovaraju profilu', labelEn: 'Information mismatch' },
  { value: 'custom', label: 'Drugi razlog...', labelEn: 'Custom reason...' },
] as const;

/**
 * Status badge configuration.
 */
export const STATUS_BADGE_CONFIG: Record<DriverLicenseStatus, { color: string; icon: string; label: string }> = {
  NOT_SUBMITTED: { color: 'neutral', icon: 'help_outline', label: 'Nije podneto' },
  PENDING_DOCUMENTS: { color: 'neutral', icon: 'upload_file', label: 'Čekaju se dokumenti' },
  PENDING_REVIEW: { color: 'warn', icon: 'schedule', label: 'Čeka pregled' },
  APPROVED: { color: 'success', icon: 'check_circle', label: 'Odobreno' },
  REJECTED: { color: 'error', icon: 'cancel', label: 'Odbijeno' },
  EXPIRED: { color: 'warn', icon: 'event_busy', label: 'Isteklo' },
  SUSPENDED: { color: 'error', icon: 'block', label: 'Suspendovano' },
};

/**
 * Confidence level thresholds for OCR/biometric scores.
 */
export const CONFIDENCE_THRESHOLDS = {
  LOW: 70,      // Below this = red indicator
  MEDIUM: 85,   // Below this = yellow indicator
  HIGH: 95,     // At or above = green indicator
} as const;

/**
 * Get confidence level from percentage.
 */
export function getConfidenceLevel(percent?: number): 'low' | 'medium' | 'high' | 'excellent' {
  if (percent == null) return 'low';
  if (percent < CONFIDENCE_THRESHOLDS.LOW) return 'low';
  if (percent < CONFIDENCE_THRESHOLDS.MEDIUM) return 'medium';
  if (percent < CONFIDENCE_THRESHOLDS.HIGH) return 'high';
  return 'excellent';
}

/**
 * Get CSS class for confidence level.
 */
export function getConfidenceClass(percent?: number): string {
  const level = getConfidenceLevel(percent);
  return `confidence-${level}`;
}