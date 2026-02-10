/**
 * Photo Guidance Models
 *
 * These models support the guided photo capture workflow.
 * They mirror the backend PhotoGuidanceDTO and PhotoSequenceValidationDTO.
 *
 * @since Enterprise Upgrade Phase 2
 */

import { CheckInPhotoType } from './check-in.model';

/**
 * Photo angles for positioning guidance.
 */
export type PhotoAngle =
  | 'FRONT_FACING'
  | 'FRONT_LEFT_45'
  | 'LEFT_PROFILE'
  | 'REAR_LEFT_45'
  | 'REAR_FACING'
  | 'REAR_RIGHT_45'
  | 'RIGHT_PROFILE'
  | 'FRONT_RIGHT_45'
  | 'DASHBOARD'
  | 'FRONT_SEATS'
  | 'REAR_SEATS'
  | 'TRUNK_BOOT'
  | 'ODOMETER_CLOSEUP'
  | 'FUEL_GAUGE_CLOSEUP';

/**
 * Guidance for capturing a specific photo type.
 */
export interface PhotoGuidanceDTO {
  /** Photo type this guidance applies to */
  photoType: CheckInPhotoType;

  /** Display name (Serbian) */
  displayName: string;

  /** Display name (English) */
  displayNameEn: string;

  /** Sequence order (1-8 for exterior, 9-12 for interior) */
  sequenceOrder: number;

  /** Total photos in this category */
  totalInCategory: number;

  /** Category: 'exterior', 'interior', or 'reading' */
  category: 'exterior' | 'interior' | 'reading' | 'other';

  /** Detailed instructions (Serbian) */
  instructionsSr: string;

  /** Detailed instructions (English) */
  instructionsEn: string;

  /** URL to silhouette overlay SVG */
  silhouetteUrl: string;

  /** Expected camera angle */
  expectedAngle: PhotoAngle;

  /** Estimated capture time in seconds */
  estimatedDuration: number;

  /** Common mistakes to avoid (Serbian) */
  commonMistakesSr: string[];

  /** Common mistakes to avoid (English) */
  commonMistakesEn: string[];

  /** Tips for a good photo (Serbian) */
  tipsSr: string[];

  /** Tips for a good photo (English) */
  tipsEn: string[];

  /** Whether this photo is required */
  required: boolean;

  /** Minimum distance from vehicle (meters) */
  minDistanceMeters?: number;

  /** Maximum distance from vehicle (meters) */
  maxDistanceMeters?: number;

  /** What should be visible in this photo (Serbian) */
  visibilityChecklistSr: string[];

  /** What should be visible in this photo (English) */
  visibilityChecklistEn: string[];
}

/**
 * Result of validating photo sequence completeness.
 */
export interface PhotoSequenceValidationDTO {
  /** Whether the sequence is valid (all required photos present) */
  valid: boolean;

  /** List of missing photo types */
  missingTypes?: CheckInPhotoType[];

  /** List of photo types with validation issues */
  invalidTypes?: CheckInPhotoType[];

  /** Total photos uploaded */
  uploadedCount: number;

  /** Total photos required */
  requiredCount: number;

  /** Completion percentage (0-100) */
  completionPercentage: number;

  /** Human-readable message (Serbian) */
  messageSr: string;

  /** Human-readable message (English) */
  messageEn: string;

  /** Whether ready to proceed to handshake */
  readyForHandshake: boolean;
}

/**
 * Photo capture status during guided capture.
 */
export interface PhotoCaptureStatus {
  photoType: CheckInPhotoType;
  status: 'pending' | 'capturing' | 'captured' | 'uploading' | 'uploaded' | 'rejected';
  localPreviewUrl?: string;
  blob?: Blob;
  uploadedPhotoId?: number;
  rejectionReason?: string;
}

/**
 * Batch submission for guest check-in photos.
 */
export interface GuestCheckInPhotoSubmissionDTO {
  photos: GuestCheckInPhotoItem[];
  clientCapturedAt?: string;
  deviceInfo?: string;
}

export interface GuestCheckInPhotoItem {
  photoType: CheckInPhotoType;
  base64Data: string;
  filename: string;
  mimeType?: string;
  latitude?: number;
  longitude?: number;
  capturedAt?: string;
}

/**
 * Response from guest check-in photo upload.
 */
export interface GuestCheckInPhotoResponseDTO {
  success: boolean;
  httpStatus: number;
  userMessage: string;
  processedPhotos: ProcessedPhotoDTO[];
  acceptedCount: number;
  rejectedCount: number;
  allRequiredPhotosSubmitted: boolean;
  guestPhotosComplete: boolean;
  missingRequiredCount: number;
  missingPhotoTypes?: string[];
  detectedDiscrepancies?: PhotoDiscrepancySummaryDTO[];
  processedAt: string;
  sessionId: string;
}

export interface ProcessedPhotoDTO {
  photoId?: number;
  photoType: CheckInPhotoType;
  accepted: boolean;
  rejectionReason?: string;
  exifValidationStatus?: string;
  /** Photo URL from backend (matches CheckInPhotoDTO.url serialization) */
  url?: string;
}

export interface PhotoDiscrepancySummaryDTO {
  discrepancyId: number;
  photoType: CheckInPhotoType;
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  description: string;
  guestPhotoId: number;
  hostPhotoId?: number;
}

/**
 * Required guest check-in photo types (8 required).
 */
export const REQUIRED_GUEST_CHECKIN_TYPES: CheckInPhotoType[] = [
  'GUEST_EXTERIOR_FRONT',
  'GUEST_EXTERIOR_REAR',
  'GUEST_EXTERIOR_LEFT',
  'GUEST_EXTERIOR_RIGHT',
  'GUEST_INTERIOR_DASHBOARD',
  'GUEST_INTERIOR_REAR',
  'GUEST_ODOMETER',
  'GUEST_FUEL_GAUGE',
];

/**
 * Required host checkout photo types (8 required).
 */
export const REQUIRED_HOST_CHECKOUT_TYPES: CheckInPhotoType[] = [
  'HOST_CHECKOUT_EXTERIOR_FRONT',
  'HOST_CHECKOUT_EXTERIOR_REAR',
  'HOST_CHECKOUT_EXTERIOR_LEFT',
  'HOST_CHECKOUT_EXTERIOR_RIGHT',
  'HOST_CHECKOUT_INTERIOR_DASHBOARD',
  'HOST_CHECKOUT_INTERIOR_REAR',
  'HOST_CHECKOUT_ODOMETER',
  'HOST_CHECKOUT_FUEL_GAUGE',
];

/**
 * Maps guest check-in photo type to corresponding host photo type.
 */
export function getCorrespondingHostType(guestType: CheckInPhotoType): CheckInPhotoType | null {
  const mapping: Partial<Record<CheckInPhotoType, CheckInPhotoType>> = {
    GUEST_EXTERIOR_FRONT: 'HOST_EXTERIOR_FRONT',
    GUEST_EXTERIOR_REAR: 'HOST_EXTERIOR_REAR',
    GUEST_EXTERIOR_LEFT: 'HOST_EXTERIOR_LEFT',
    GUEST_EXTERIOR_RIGHT: 'HOST_EXTERIOR_RIGHT',
    GUEST_INTERIOR_DASHBOARD: 'HOST_INTERIOR_DASHBOARD',
    GUEST_INTERIOR_REAR: 'HOST_INTERIOR_REAR',
    GUEST_ODOMETER: 'HOST_ODOMETER',
    GUEST_FUEL_GAUGE: 'HOST_FUEL_GAUGE',
  };
  return mapping[guestType] ?? null;
}
