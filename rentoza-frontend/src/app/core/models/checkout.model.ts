/**
 * Check-Out Workflow - Frontend Models
 *
 * These models mirror the backend DTOs for checkout workflow.
 * @see CheckOutStatusDTO, GuestCheckOutSubmissionDTO, etc.
 */

import { CheckInPhotoDTO, CarSummaryDTO, CheckInPhotoType } from './check-in.model';

// ============================================================================
// CHECKOUT STATE MACHINE
// ============================================================================

/**
 * Checkout workflow states (mirrors backend BookingStatus for checkout flow)
 */
export type CheckoutState =
  | 'IN_TRIP'           // Trip in progress, checkout not started
  | 'CHECKOUT_OPEN'     // Checkout window opened
  | 'GUEST_COMPLETE'    // Guest submitted return condition
  | 'HOST_CONFIRMED'    // Host confirmed vehicle return
  | 'COMPLETED';        // Trip fully completed

// ============================================================================
// RESPONSE DTOs
// ============================================================================

export interface CheckOutStatusDTO {
  bookingId: number;
  checkoutSessionId: string | null;
  status: string; // Backend BookingStatus

  // Phase completion
  checkoutWindowOpen: boolean;
  guestCheckOutComplete: boolean;
  hostCheckOutComplete: boolean;
  checkoutComplete: boolean;

  // Timestamps (ISO strings from backend)
  checkoutOpenedAt: string | null;
  guestCompletedAt: string | null;
  hostCompletedAt: string | null;
  checkoutCompletedAt: string | null;

  // Trip timing
  tripStartedAt: string | null;
  scheduledReturnTime: string | null;
  actualReturnTime: string | null;
  lateReturnMinutes: number | null;
  lateFeeAmount: number | null;

  // Odometer & Fuel
  startOdometer: number | null;
  endOdometer: number | null;
  totalMileage: number | null;
  startFuelLevel: number | null;
  endFuelLevel: number | null;
  fuelDifference: number | null;

  // Photos
  checkInPhotos: CheckInPhotoDTO[];
  checkoutPhotos: CheckInPhotoDTO[];
  hostCheckoutPhotos: CheckInPhotoDTO[];

  // Damage assessment
  newDamageReported: boolean;
  damageDescription: string | null;
  damageClaimAmount: number | null;
  damageClaimStatus: string | null;

  // Role-specific flags
  isHost: boolean;
  isGuest: boolean;

  // Car info
  car: CarSummaryDTO;
}

// ============================================================================
// REQUEST DTOs
// ============================================================================

export interface GuestCheckOutSubmissionDTO {
  bookingId: number;
  endOdometerReading: number;
  endFuelLevelPercent: number;
  conditionComment?: string;
  guestLatitude?: number;
  guestLongitude?: number;
}

export interface HostCheckOutConfirmationDTO {
  bookingId: number;
  conditionAccepted: boolean;
  newDamageReported?: boolean;
  damageDescription?: string;
  estimatedDamageCostRsd?: number;
  damagePhotoIds?: number[];
  hostLatitude?: number;
  hostLongitude?: number;
  notes?: string;
}

// ============================================================================
// UI STATE
// ============================================================================

export interface CheckoutWizardState {
  currentStep: CheckoutWizardStep;
  status: CheckOutStatusDTO | null;
  uploadProgress: Map<string, PhotoUploadProgress>;
  isLoading: boolean;
  error: string | null;
}

export type CheckoutWizardStep =
  | 'loading'
  | 'review-checkin'      // Guest reviews check-in photos
  | 'checkout-photos'     // Guest uploads return photos
  | 'odometer-fuel'       // Guest enters end readings
  | 'submit'              // Guest submits checkout
  | 'waiting-host'        // Guest waiting for host confirmation
  | 'host-confirm'        // Host reviews and confirms
  | 'damage-report'       // Host reports damage
  | 'complete';           // Checkout done

export interface PhotoUploadProgress {
  slotId: string;
  photoType: CheckInPhotoType;
  state: 'compressing' | 'uploading' | 'validating' | 'complete' | 'error';
  progress: number;
  error?: string;
  result?: CheckInPhotoDTO;
}

// ============================================================================
// CHECKOUT PHOTO SLOTS
// ============================================================================

export interface CheckoutPhotoSlot {
  type: CheckInPhotoType;
  label: string;
  icon: string;
  required: boolean;
}

export const CHECKOUT_PHOTO_SLOTS: CheckoutPhotoSlot[] = [
  { type: 'CHECKOUT_EXTERIOR_FRONT', label: 'Prednja strana', icon: 'directions_car', required: true },
  { type: 'CHECKOUT_EXTERIOR_REAR', label: 'Zadnja strana', icon: 'directions_car', required: true },
  { type: 'CHECKOUT_EXTERIOR_LEFT', label: 'Leva strana', icon: 'directions_car', required: true },
  { type: 'CHECKOUT_EXTERIOR_RIGHT', label: 'Desna strana', icon: 'directions_car', required: true },
  { type: 'CHECKOUT_ODOMETER', label: 'Kilometraža', icon: 'speed', required: true },
  { type: 'CHECKOUT_FUEL_GAUGE', label: 'Nivo goriva', icon: 'local_gas_station', required: true },
];

export const OPTIONAL_CHECKOUT_PHOTO_SLOTS: CheckoutPhotoSlot[] = [
  { type: 'CHECKOUT_INTERIOR_DASHBOARD', label: 'Instrument tabla', icon: 'dashboard', required: false },
  { type: 'CHECKOUT_INTERIOR_REAR', label: 'Zadnja sedišta', icon: 'event_seat', required: false },
  { type: 'CHECKOUT_DAMAGE_NEW', label: 'Nova oštećenja', icon: 'report_problem', required: false },
  { type: 'CHECKOUT_CUSTOM', label: 'Dodatna fotografija', icon: 'add_a_photo', required: false },
];

// ============================================================================
// DAMAGE CLAIM STATUS
// ============================================================================

export type DamageClaimStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'PAID';

export const DAMAGE_CLAIM_STATUS_LABELS: Record<DamageClaimStatus, string> = {
  PENDING: 'Na čekanju',
  APPROVED: 'Odobreno',
  REJECTED: 'Odbijeno',
  PAID: 'Plaćeno',
};


