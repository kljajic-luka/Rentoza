/**
 * Check-In Handshake Protocol - Frontend Models
 *
 * These models mirror the Phase 2 backend DTOs exactly.
 * @see CheckInStatusDTO, HostCheckInSubmissionDTO, etc.
 */

// ============================================================================
// ENUMS
// ============================================================================

export type CheckInPhotoType =
  | 'HOST_EXTERIOR_FRONT'
  | 'HOST_EXTERIOR_REAR'
  | 'HOST_EXTERIOR_LEFT'
  | 'HOST_EXTERIOR_RIGHT'
  | 'HOST_INTERIOR_DASHBOARD'
  | 'HOST_INTERIOR_REAR'
  | 'HOST_ODOMETER'
  | 'HOST_FUEL_GAUGE'
  | 'HOST_DAMAGE_PREEXISTING'
  | 'HOST_CUSTOM'
  | 'GUEST_DAMAGE_NOTED'
  | 'GUEST_HOTSPOT'
  // Guest check-in photos (dual-party verification)
  | 'GUEST_EXTERIOR_FRONT'
  | 'GUEST_EXTERIOR_REAR'
  | 'GUEST_EXTERIOR_LEFT'
  | 'GUEST_EXTERIOR_RIGHT'
  | 'GUEST_INTERIOR_DASHBOARD'
  | 'GUEST_INTERIOR_REAR'
  | 'GUEST_ODOMETER'
  | 'GUEST_FUEL_GAUGE'
  | 'GUEST_CUSTOM'
  // Checkout photo types
  | 'CHECKOUT_EXTERIOR_FRONT'
  | 'CHECKOUT_EXTERIOR_REAR'
  | 'CHECKOUT_EXTERIOR_LEFT'
  | 'CHECKOUT_EXTERIOR_RIGHT'
  | 'CHECKOUT_INTERIOR_DASHBOARD'
  | 'CHECKOUT_INTERIOR_REAR'
  | 'CHECKOUT_ODOMETER'
  | 'CHECKOUT_FUEL_GAUGE'
  | 'CHECKOUT_DAMAGE_NEW'
  | 'CHECKOUT_CUSTOM'
  // Host checkout confirmation photos
  | 'HOST_CHECKOUT_CONFIRMATION'
  | 'HOST_CHECKOUT_DAMAGE_EVIDENCE'
  // Host checkout photos (dual-party verification)
  | 'HOST_CHECKOUT_EXTERIOR_FRONT'
  | 'HOST_CHECKOUT_EXTERIOR_REAR'
  | 'HOST_CHECKOUT_EXTERIOR_LEFT'
  | 'HOST_CHECKOUT_EXTERIOR_RIGHT'
  | 'HOST_CHECKOUT_INTERIOR_DASHBOARD'
  | 'HOST_CHECKOUT_INTERIOR_REAR'
  | 'HOST_CHECKOUT_ODOMETER'
  | 'HOST_CHECKOUT_FUEL_GAUGE'
  | 'HOST_CHECKOUT_CUSTOM';

export type ExifValidationStatus =
  | 'PENDING'
  | 'VALID'
  | 'VALID_NO_GPS'
  | 'VALID_WITH_WARNINGS'
  | 'REJECTED_TOO_OLD'
  | 'REJECTED_NO_EXIF'
  | 'REJECTED_LOCATION_MISMATCH'
  | 'REJECTED_NO_GPS'
  | 'REJECTED_FUTURE_TIMESTAMP'
  | 'OVERRIDE_APPROVED';

export type HotspotLocation =
  | 'FRONT_BUMPER'
  | 'REAR_BUMPER'
  | 'FRONT_LEFT_FENDER'
  | 'FRONT_RIGHT_FENDER'
  | 'REAR_LEFT_FENDER'
  | 'REAR_RIGHT_FENDER'
  | 'LEFT_DOOR_FRONT'
  | 'LEFT_DOOR_REAR'
  | 'RIGHT_DOOR_FRONT'
  | 'RIGHT_DOOR_REAR'
  | 'HOOD'
  | 'ROOF'
  | 'TRUNK'
  | 'WINDSHIELD'
  | 'REAR_WINDOW'
  | 'LEFT_MIRROR'
  | 'RIGHT_MIRROR'
  | 'LEFT_HEADLIGHT'
  | 'RIGHT_HEADLIGHT'
  | 'LEFT_TAILLIGHT'
  | 'RIGHT_TAILLIGHT'
  | 'WHEEL_FRONT_LEFT'
  | 'WHEEL_FRONT_RIGHT'
  | 'WHEEL_REAR_LEFT'
  | 'WHEEL_REAR_RIGHT'
  | 'INTERIOR_DASHBOARD'
  | 'INTERIOR_SEATS'
  | 'INTERIOR_OTHER'
  | 'OTHER';

// ============================================================================
// CHECK-IN STATE MACHINE
// ============================================================================

/**
 * Check-in workflow states (mirrors backend BookingStatus for check-in flow)
 */
export type CheckInState =
  | 'NOT_READY' // Before T-24h
  | 'CHECK_IN_OPEN' // Window opened, awaiting host
  | 'HOST_SUBMITTED' // Host completed, awaiting guest
  | 'GUEST_ACKNOWLEDGED' // Guest confirmed, awaiting handshake
  | 'HANDSHAKE_PENDING' // Both ready, confirming handshake
  | 'TRIP_ACTIVE' // Handshake complete, trip started
  | 'NO_SHOW_HOST' // Host failed to complete
  | 'NO_SHOW_GUEST'; // Guest failed to complete

// ============================================================================
// RESPONSE DTOs
// ============================================================================

export interface CheckInStatusDTO {
  bookingId: number;
  checkInSessionId: string;
  status: string; // Backend BookingStatus

  // Phase completion
  hostCheckInComplete: boolean;
  guestCheckInComplete: boolean;
  guestAcknowledged: boolean;
  handshakeReady: boolean;
  hostCheckedIn: boolean;
  handshakeComplete: boolean;

  // Timestamps (ISO strings from backend)
  checkInOpenedAt: string | null;
  hostCompletedAt: string | null;
  guestCompletedAt: string | null;
  handshakeCompletedAt: string | null;
  bookingStartTime: string | null;

  // Host data (visible to guest after host completes)
  vehiclePhotos: CheckInPhotoDTO[];
  odometerReading: number | null;
  fuelLevelPercent: number | null;

  // Remote handoff
  lockboxAvailable: boolean;
  geofenceValid: boolean;
  geofenceDistanceMeters: number | null;

  // Deadlines
  tripStartScheduled: string;
  noShowDeadline: string | null;
  minutesUntilNoShow: number | null;

  // Role-specific flags (NOTE: Jackson serializes isHost/isGuest as host/guest)
  host: boolean;
  guest: boolean;

  // Car info
  car: CarSummaryDTO;

  // =========================================================================
  // Pickup Location (Phase 4: Pickup Location Display Feature)
  // =========================================================================
  /** Pickup location latitude (from booking or car home fallback) */
  pickupLatitude?: number;
  /** Pickup location longitude */
  pickupLongitude?: number;
  /** Pickup address (street + number) */
  pickupAddress?: string;
  /** Pickup city */
  pickupCity?: string;
  /** Pickup zip code */
  pickupZipCode?: string;
  /**
   * @deprecated Phase 2 - Location variance validation removed.
   * Kept for backward compatibility with pre-Phase2 status objects.
   * Will be removed in Phase 3.
   */
  pickupLocationVarianceMeters?: number;
  /**
   * @deprecated Phase 2 - Variance badges removed from UI.
   * Backend no longer calculates variance. Use audit trail instead.
   */
  varianceStatus?: 'NONE' | 'WARNING' | 'BLOCKING';
  /** True if pickup location is an estimate (fell back to car home location) */
  estimatedLocation?: boolean;
  /** Source of estimate: "CAR_HOME_LOCATION" when fallback used */
  estimatedLocationSource?: string;
}

export interface CarSummaryDTO {
  id: number;
  brand: string;
  model: string;
  year: number;
  imageUrl: string | null;
}

export interface CheckInPhotoDTO {
  photoId: number;
  photoType: CheckInPhotoType;
  url: string;
  uploadedAt: string;
  exifValidationStatus: ExifValidationStatus;
  exifValidationMessage: string | null;
  width: number | null;
  height: number | null;
  mimeType: string | null;
  exifTimestamp: string | null;
  exifLatitude: number | null;
  exifLongitude: number | null;
  deviceModel: string | null;

  // ========== Rejection Fields (Phase 1: Rejected Photo Infrastructure) ==========

  /**
   * Whether the photo was accepted by EXIF validation.
   * false = rejected (not stored), true = accepted (stored to DB).
   */
  accepted?: boolean;

  /**
   * User-friendly rejection reason in Serbian.
   * Only populated when accepted=false.
   */
  rejectionReason?: string;

  /**
   * Actionable hint for the user to fix the rejection issue.
   * Only populated when accepted=false.
   */
  remediationHint?: string;
}

// ============================================================================
// VIEW MODELS (Svelte-Inspired Reactive Patterns)
// ============================================================================

/**
 * Photo slot configuration (type, label, icon).
 * Defines the metadata for each required photo in the check-in flow.
 */
export interface PhotoSlot {
  type: CheckInPhotoType;
  label: string;
  icon: string;
  required: boolean;
}

/**
 * Svelte-inspired flattened view model for photo slots.
 * Eliminates template method calls and redundant Map lookups by computing
 * all derived state in a single pass using Angular computed signals.
 *
 * Pattern: Single-pass derivation from CheckInService.uploadProgress() Map.
 * Includes Critical Fix #1: Location validation tracking (locationMismatch, distanceMeters).
 *
 * @see host-check-in.component.ts photoSlotViewModels computed signal
 */
export interface PhotoSlotViewModel {
  /** Photo slot configuration (type, label, icon) */
  slot: PhotoSlot;

  /** Upload completion status */
  isCompleted: boolean;

  /** Upload in progress (>0% and <100%) */
  isUploading: boolean;

  /** Backend validation pending */
  isValidationPending: boolean;

  /** Photo rejected by backend validation */
  isRejected: boolean;

  /** Upload progress percentage (0-100) */
  progress: number;

  /** Preview URL (either local blob or server URL) */
  previewUrl: string | null;

  /** Rejection reason in Serbian (if rejected) */
  rejectionReason: string | null;

  /** Actionable remediation hint (if rejected) */
  remediationHint: string | null;

  // Critical Fix #1: Location validation tracking
  /** Photo taken at different location than car (fraud detection) */
  locationMismatch: boolean;

  /** Distance in meters between photo GPS and car location (if location mismatch) */
  distanceMeters: number | null;
}

/**
 * Svelte-inspired aggregate stats view model.
 * Replaces individual computed signals (completedPhotosCount, allRequiredPhotosUploaded, etc.)
 * with a single stats object computed once per state change.
 *
 * Pattern: Aggregate derivation eliminates multiple iterations over photo state.
 */
export interface PhotoStatsViewModel {
  /** Number of required photos completed */
  completed: number;

  /** Total number of required photos */
  total: number;

  /** All required photos uploaded and validated */
  allRequiredComplete: boolean;

  /** Any upload currently in progress */
  anyUploading: boolean;

  /** Number of photos pending backend validation */
  validationPendingCount: number;

  /** Number of rejected photos requiring re-upload */
  rejectedCount: number;

  /** Number of location mismatch detections (Critical Fix #1) */
  locationMismatchCount: number;
}

// ============================================================================
// REQUEST DTOs
// ============================================================================

/**
 * Phase 2 Simplification (Turo-Style): Car location removed from submission.
 * Backend derives car location from first photo's EXIF GPS metadata.
 *
 * @see CheckInService.submitHostCheckIn() - Car position no longer submitted
 * @see Backend: CheckInService.completeHostCheckIn() - Location derived from photos
 */
export interface HostCheckInSubmissionDTO {
  bookingId: number;
  odometerReading: number;
  fuelLevelPercent: number;
  photoIds: number[];
  lockboxCode?: string;
  /** Host's GPS position at submission (optional for audit trail) */
  hostLatitude?: number;
  hostLongitude?: number;
}

export interface GuestConditionAcknowledgmentDTO {
  bookingId: number;
  conditionAccepted: boolean;
  hotspots?: HotspotMarkingDTO[];
  guestLatitude: number;
  guestLongitude: number;
  conditionComment?: string;
}

export interface HotspotMarkingDTO {
  location: HotspotLocation;
  description: string;
  photoId?: number;
}

export interface HandshakeConfirmationDTO {
  bookingId: number;
  confirmed: boolean;
  hostVerifiedPhysicalId?: boolean;
  latitude?: number;
  longitude?: number;
  deviceFingerprint?: string;
}

// ============================================================================
// PHOTO UPLOAD
// ============================================================================

export interface PhotoUploadRequest {
  file: File;
  photoType: CheckInPhotoType;
  clientTimestamp: string; // ISO string - critical for basement problem fix
}

/**
 * Photo upload progress tracking.
 *
 * `slotId` is the map key: for required photos it equals `photoType` (e.g., 'HOST_EXTERIOR_FRONT'),
 * for damage photos it's a UUID (e.g., 'damage-a1b2c3d4').
 *
 * NOTE: When a user re-uploads to the same slot, the previous backend photo becomes orphaned.
 * This minor storage cost is accepted for UX speed; backend cleanup deferred to Phase 4.
 *
 * ## Rejection Handling (Phase 1: Rejected Photo Infrastructure)
 * - State 'rejected' indicates EXIF validation failure
 * - Rejected photos are NOT stored (zero-storage policy)
 * - User receives rejectionReason and remediationHint for guidance
 * - retryCount tracks number of attempts for soft retry limit (3)
 */
export interface PhotoUploadProgress {
  slotId: string; // UUID for damage photos, enum string for required photos
  photoType: CheckInPhotoType;
  /** Upload state. 'rejected' = EXIF validation failed, photo not stored */
  state: 'compressing' | 'uploading' | 'validating' | 'complete' | 'error' | 'rejected';
  progress: number; // 0-100
  error?: string;
  result?: CheckInPhotoDTO;

  // ========== Rejection Fields (Phase 1: Rejected Photo Infrastructure) ==========

  /**
   * User-friendly rejection reason in Serbian.
   * Only populated when state='rejected'.
   * Example: "Fotografija je prestara. Mora biti snimljena u poslednjih 30 minuta."
   */
  rejectionReason?: string;

  /**
   * Actionable hint for the user to fix the rejection issue.
   * Only populated when state='rejected'.
   * Example: "Otvorite kameru na telefonu i napravite novu fotografiju."
   */
  remediationHint?: string;

  /**
   * Machine-readable error code for analytics/debugging.
   * Only populated when state='rejected'.
   * Example: "PHOTO_TOO_OLD", "NO_EXIF_DATA"
   */
  errorCode?: string;

  /**
   * Number of upload attempts for this slot (including rejections).
   * Used for soft retry limit (3 attempts with warning).
   */
  retryCount?: number;
}

/**
 * Backend response envelope for photo uploads.
 * Maps to PhotoUploadResponse.java on the backend.
 */
export interface PhotoUploadResponse {
  accepted: boolean;
  photo: CheckInPhotoDTO;
  httpStatus: number;
  userMessage: string;
  errorCodes?: string[];
}

/**
 * Dynamic damage photo slot for documenting pre-existing vehicle damage.
 * Host can add multiple damage photos beyond the 8 required slots.
 */
export interface DamagePhotoSlot {
  id: string; // UUID - unique identifier for this slot
  photoType: 'HOST_DAMAGE_PREEXISTING';
  description?: string; // Optional damage description (e.g., "Scratch on front bumper")
}

/** Maximum allowed damage photos per check-in (prevents abuse) */
export const MAX_DAMAGE_PHOTOS = 10;

// ============================================================================
// OFFLINE QUEUE
// ============================================================================

export interface QueuedUpload {
  id: string; // UUID for tracking
  bookingId: number;
  photoType: CheckInPhotoType;
  file: Blob;
  clientTimestamp: string;
  retryCount: number;
  createdAt: string;
  lastAttempt?: string;
  error?: string;
}

// ============================================================================
// GEOLOCATION
// ============================================================================

export interface GeolocationResult {
  latitude: number;
  longitude: number;
  accuracy: number; // in meters
  timestamp: number;
}

export interface GeolocationError {
  code: 'PERMISSION_DENIED' | 'POSITION_UNAVAILABLE' | 'TIMEOUT' | 'UNSUPPORTED';
  message: string;
}

// ============================================================================
// UI STATE
// ============================================================================

export interface CheckInWizardState {
  currentStep: WizardStep;
  status: CheckInStatusDTO | null;
  uploadProgress: Map<CheckInPhotoType, PhotoUploadProgress>;
  geolocation: GeolocationResult | null;
  geolocationError: GeolocationError | null;
  isLoading: boolean;
  error: string | null;
}

export type WizardStep =
  | 'loading'
  | 'photo-upload'
  | 'odometer-fuel'
  | 'review'
  | 'condition-ack'
  | 'hotspot-marking'
  | 'handshake'
  | 'complete';

// ============================================================================
// REQUIRED PHOTOS CONFIGURATION
// ============================================================================

export const REQUIRED_HOST_PHOTOS: CheckInPhotoType[] = [
  'HOST_EXTERIOR_FRONT',
  'HOST_EXTERIOR_REAR',
  'HOST_EXTERIOR_LEFT',
  'HOST_EXTERIOR_RIGHT',
  'HOST_INTERIOR_DASHBOARD',
  'HOST_INTERIOR_REAR',
  'HOST_ODOMETER',
  'HOST_FUEL_GAUGE',
];

export const PHOTO_TYPE_LABELS: Record<CheckInPhotoType, string> = {
  // Host check-in photos
  HOST_EXTERIOR_FRONT: 'Prednja strana',
  HOST_EXTERIOR_REAR: 'Zadnja strana',
  HOST_EXTERIOR_LEFT: 'Leva strana',
  HOST_EXTERIOR_RIGHT: 'Desna strana',
  HOST_INTERIOR_DASHBOARD: 'Instrument tabla',
  HOST_INTERIOR_REAR: 'Zadnja sedišta',
  HOST_ODOMETER: 'Kilometraža',
  HOST_FUEL_GAUGE: 'Nivo goriva',
  HOST_DAMAGE_PREEXISTING: 'Postojeća oštećenja',
  HOST_CUSTOM: 'Dodatna fotografija',
  // Guest check-in photos (dual-party verification)
  GUEST_EXTERIOR_FRONT: 'Gost - Prednja strana',
  GUEST_EXTERIOR_REAR: 'Gost - Zadnja strana',
  GUEST_EXTERIOR_LEFT: 'Gost - Leva strana',
  GUEST_EXTERIOR_RIGHT: 'Gost - Desna strana',
  GUEST_INTERIOR_DASHBOARD: 'Gost - Instrument tabla',
  GUEST_INTERIOR_REAR: 'Gost - Zadnja sedišta',
  GUEST_ODOMETER: 'Gost - Kilometraža',
  GUEST_FUEL_GAUGE: 'Gost - Nivo goriva',
  GUEST_CUSTOM: 'Gost - Dodatna fotografija',
  GUEST_DAMAGE_NOTED: 'Primećena oštećenja',
  GUEST_HOTSPOT: 'Označena tačka',
  // Checkout photos
  CHECKOUT_EXTERIOR_FRONT: 'Povratak - Prednja strana',
  CHECKOUT_EXTERIOR_REAR: 'Povratak - Zadnja strana',
  CHECKOUT_EXTERIOR_LEFT: 'Povratak - Leva strana',
  CHECKOUT_EXTERIOR_RIGHT: 'Povratak - Desna strana',
  CHECKOUT_INTERIOR_DASHBOARD: 'Povratak - Instrument tabla',
  CHECKOUT_INTERIOR_REAR: 'Povratak - Zadnja sedišta',
  CHECKOUT_ODOMETER: 'Povratak - Kilometraža',
  CHECKOUT_FUEL_GAUGE: 'Povratak - Nivo goriva',
  CHECKOUT_DAMAGE_NEW: 'Nova oštećenja',
  CHECKOUT_CUSTOM: 'Povratak - Dodatna fotografija',
  // Host checkout confirmation
  HOST_CHECKOUT_CONFIRMATION: 'Potvrda povratka',
  HOST_CHECKOUT_DAMAGE_EVIDENCE: 'Dokaz oštećenja',
  // Host checkout photos (dual-party verification)
  HOST_CHECKOUT_EXTERIOR_FRONT: 'Host checkout - Prednja strana',
  HOST_CHECKOUT_EXTERIOR_REAR: 'Host checkout - Zadnja strana',
  HOST_CHECKOUT_EXTERIOR_LEFT: 'Host checkout - Leva strana',
  HOST_CHECKOUT_EXTERIOR_RIGHT: 'Host checkout - Desna strana',
  HOST_CHECKOUT_INTERIOR_DASHBOARD: 'Host checkout - Instrument tabla',
  HOST_CHECKOUT_INTERIOR_REAR: 'Host checkout - Zadnja sedišta',
  HOST_CHECKOUT_ODOMETER: 'Host checkout - Kilometraža',
  HOST_CHECKOUT_FUEL_GAUGE: 'Host checkout - Nivo goriva',
  HOST_CHECKOUT_CUSTOM: 'Host checkout - Dodatna fotografija',
};

// ============================================================================
// CHECKOUT REQUIRED PHOTOS
// ============================================================================

export const REQUIRED_CHECKOUT_PHOTOS: CheckInPhotoType[] = [
  'CHECKOUT_EXTERIOR_FRONT',
  'CHECKOUT_EXTERIOR_REAR',
  'CHECKOUT_EXTERIOR_LEFT',
  'CHECKOUT_EXTERIOR_RIGHT',
  'CHECKOUT_ODOMETER',
  'CHECKOUT_FUEL_GAUGE',
];

export const HOTSPOT_LOCATION_LABELS: Record<HotspotLocation, string> = {
  FRONT_BUMPER: 'Prednji branik',
  REAR_BUMPER: 'Zadnji branik',
  FRONT_LEFT_FENDER: 'Prednji levi blatobran',
  FRONT_RIGHT_FENDER: 'Prednji desni blatobran',
  REAR_LEFT_FENDER: 'Zadnji levi blatobran',
  REAR_RIGHT_FENDER: 'Zadnji desni blatobran',
  LEFT_DOOR_FRONT: 'Prednja leva vrata',
  LEFT_DOOR_REAR: 'Zadnja leva vrata',
  RIGHT_DOOR_FRONT: 'Prednja desna vrata',
  RIGHT_DOOR_REAR: 'Zadnja desna vrata',
  HOOD: 'Hauba',
  ROOF: 'Krov',
  TRUNK: 'Gepek',
  WINDSHIELD: 'Vetrobransko staklo',
  REAR_WINDOW: 'Zadnje staklo',
  LEFT_MIRROR: 'Levi retrovizor',
  RIGHT_MIRROR: 'Desni retrovizor',
  LEFT_HEADLIGHT: 'Levi far',
  RIGHT_HEADLIGHT: 'Desni far',
  LEFT_TAILLIGHT: 'Levo stop svetlo',
  RIGHT_TAILLIGHT: 'Desno stop svetlo',
  WHEEL_FRONT_LEFT: 'Prednji levi točak',
  WHEEL_FRONT_RIGHT: 'Prednji desni točak',
  WHEEL_REAR_LEFT: 'Zadnji levi točak',
  WHEEL_REAR_RIGHT: 'Zadnji desni točak',
  INTERIOR_DASHBOARD: 'Unutrašnjost - Kontrolna tabla',
  INTERIOR_SEATS: 'Unutrašnjost - Sedišta',
  INTERIOR_OTHER: 'Unutrašnjost - Ostalo',
  OTHER: 'Ostalo',
};
