/**
 * Cancellation Policy Models
 *
 * DTOs for the Turo-style cancellation policy system.
 * These models match the backend DTOs in `org.example.rentoza.booking.dto`.
 *
 * @since 2024-01 (Cancellation Policy Migration - Phase 3)
 */

// ==================== ENUMS ====================

/**
 * Who initiated the cancellation.
 */
export type CancelledBy = 'GUEST' | 'HOST' | 'SYSTEM';

/**
 * Reason for cancellation.
 * Used for analytics and exception handling.
 */
export type CancellationReason =
  // Guest reasons
  | 'GUEST_CHANGE_OF_PLANS'
  | 'GUEST_FOUND_ALTERNATIVE'
  | 'GUEST_EMERGENCY'
  | 'GUEST_NO_SHOW'
  // Host reasons
  | 'HOST_VEHICLE_UNAVAILABLE'
  | 'HOST_VEHICLE_DAMAGE'
  | 'HOST_EMERGENCY'
  | 'HOST_GUEST_CONCERN'
  // System reasons
  | 'SYSTEM_PAYMENT_FAILURE'
  | 'SYSTEM_POLICY_VIOLATION'
  | 'SYSTEM_VERIFICATION_FAILURE'
  | 'SYSTEM_ADMIN_ACTION';

/**
 * Status of the refund processing.
 */
export type RefundStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

// ==================== REQUEST DTOs ====================

/**
 * Request body for initiating a cancellation.
 */
export interface CancellationRequest {
  /** Why the booking is being cancelled */
  reason: CancellationReason;
  /** Optional free-text notes from user */
  notes?: string;
  /** Optional document URL for exception requests (host only) */
  documentUrl?: string;
}

// ==================== RESPONSE DTOs ====================

/**
 * Preview of cancellation consequences WITHOUT executing.
 * Shown to user before they confirm cancellation.
 */
export interface CancellationPreview {
  /** Booking being cancelled */
  bookingId: number;
  /** Who is cancelling (GUEST or HOST) */
  cancelledBy: CancelledBy;

  // Time context
  /** When the trip starts */
  tripStartTime: string; // ISO DateTime
  /** Current server time */
  currentTime: string; // ISO DateTime
  /** Hours until trip start (negative if already started) */
  hoursUntilStart: number;
  /** Whether cancellation is within 24h free window */
  isWithinFreeWindow: boolean;
  /** Whether cancellation is within 1h remorse window */
  isWithinRemorseWindow: boolean;

  // Financial breakdown
  /** Original total price of the booking */
  originalTotal: number;
  /** Penalty amount to be charged */
  penaltyAmount: number;
  /** Amount to be refunded to guest */
  refundAmount: number;
  /** Amount to be paid out to host */
  hostPayout: number;

  // Policy info
  /** Which cancellation rule was applied */
  appliedRule: string;
  /** Version of policy used for audit trail */
  policyVersion: string;

  // Host penalty info (only for HOST cancellations)
  /** Host penalty details if HOST is cancelling */
  hostPenalty?: HostPenaltyInfo;
}

/**
 * Host-specific penalty information.
 * Shown when a host is about to cancel a booking.
 */
export interface HostPenaltyInfo {
  /** Monetary penalty amount (RSD) */
  monetaryPenalty: number;
  /** Current penalty tier (1, 2, or 3+) */
  currentTier: number;
  /** Whether this cancellation will trigger account suspension */
  willTriggerSuspension: boolean;
  /** Days until current suspension ends (0 if not suspended) */
  daysUntilSuspensionEnds: number;
}

/**
 * Result after cancellation is processed.
 */
export interface CancellationResult {
  /** Whether the cancellation was successful */
  success: boolean;
  /** The booking that was cancelled */
  bookingId: number;
  /** Who cancelled */
  cancelledBy: CancelledBy;
  /** Why they cancelled */
  reason: CancellationReason;
  /** When the cancellation was processed */
  cancelledAt: string; // ISO DateTime

  // Financial outcome
  /** Original booking total */
  originalTotal: number;
  /** Penalty charged to cancelling party */
  penaltyAmount: number;
  /** Amount refunded to guest */
  refundToGuest: number;
  /** Amount paid out to host */
  payoutToHost: number;
  /** Status of the refund */
  refundStatus: RefundStatus;

  // Host-specific (for HOST cancellations)
  /** Penalty applied to host account */
  hostPenaltyApplied?: number;
  /** When host suspension ends (if triggered) */
  hostSuspendedUntil?: string; // ISO DateTime

  // Policy info
  /** Which rule was applied */
  appliedRule: string;
  /** Policy version for audit */
  policyVersion: string;
}

/**
 * Host cancellation statistics for dashboard display.
 */
export interface HostCancellationStats {
  /** Cancellations in current calendar year */
  cancellationsThisYear: number;
  /** Rolling count of cancellations in last 30 days */
  cancellationsLast30Days: number;
  /** Total bookings received by this host */
  totalBookings: number;
  /** Cancellation rate as percentage */
  cancellationRate: number;
  /** Current penalty tier (0, 1, 2, or 3) */
  currentTier: number;
  /** Penalty amount for next cancellation (RSD) */
  nextPenaltyAmount: number;
  /** Whether host is currently suspended */
  isSuspended: boolean;
  /** When suspension ends (null if not suspended) */
  suspensionEndsAt: string | null; // ISO DateTime
  /** Most recent cancellation timestamp */
  lastCancellationAt: string | null; // ISO DateTime
  /** Whether next cancellation will trigger suspension */
  willTriggerSuspension: boolean;
}

// ==================== DISPLAY HELPERS ====================

/**
 * Human-readable labels for cancellation reasons (Serbian).
 */
export const CANCELLATION_REASON_LABELS: Record<CancellationReason, string> = {
  // Guest reasons
  GUEST_CHANGE_OF_PLANS: 'Promena planova',
  GUEST_FOUND_ALTERNATIVE: 'Pronašao sam drugu opciju',
  GUEST_EMERGENCY: 'Hitna situacija',
  GUEST_NO_SHOW: 'Nisam došao na preuzimanje',
  // Host reasons
  HOST_VEHICLE_UNAVAILABLE: 'Vozilo nije dostupno',
  HOST_VEHICLE_DAMAGE: 'Vozilo je oštećeno',
  HOST_EMERGENCY: 'Hitna situacija',
  HOST_GUEST_CONCERN: 'Zabrinutost oko gosta',
  // System reasons
  SYSTEM_PAYMENT_FAILURE: 'Neuspelo plaćanje',
  SYSTEM_POLICY_VIOLATION: 'Kršenje pravila',
  SYSTEM_VERIFICATION_FAILURE: 'Neuspela verifikacija',
  SYSTEM_ADMIN_ACTION: 'Akcija administratora',
};

/**
 * Human-readable labels for applied rules (Serbian).
 */
export const APPLIED_RULE_LABELS: Record<string, string> = {
  '24H_FREE_CANCELLATION': 'Besplatno otkazivanje (više od 24h pre početka)',
  REMORSE_WINDOW: 'Zaštita impulsivne rezervacije (1h prozor)',
  SHORT_TRIP_1DAY_PENALTY: 'Kratko putovanje - jednodnevni penal',
  LONG_TRIP_50_PERCENT: 'Dugo putovanje - 50% penala',
  NO_SHOW_100_PERCENT: 'Nepojava - 100% penala',
  HOST_CANCELLATION_TIER_1: 'Prvo otkazivanje ove godine',
  HOST_CANCELLATION_TIER_2: 'Drugo otkazivanje ove godine',
  HOST_CANCELLATION_TIER_3: 'Treće+ otkazivanje ove godine (suspenzija)',
};

/**
 * Get guest-facing cancellation reasons (subset for UI dropdowns).
 */
export function getGuestReasons(): CancellationReason[] {
  return ['GUEST_CHANGE_OF_PLANS', 'GUEST_FOUND_ALTERNATIVE', 'GUEST_EMERGENCY'];
}

/**
 * Get host-facing cancellation reasons (subset for UI dropdowns).
 */
export function getHostReasons(): CancellationReason[] {
  return [
    'HOST_VEHICLE_UNAVAILABLE',
    'HOST_VEHICLE_DAMAGE',
    'HOST_EMERGENCY',
    'HOST_GUEST_CONCERN',
  ];
}