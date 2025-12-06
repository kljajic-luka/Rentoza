/**
 * Location variance status for check-in phase.
 * Indicates car position relative to agreed pickup location.
 */
export type LocationVarianceStatus = 'NONE' | 'WARNING' | 'BLOCKING';

/**
 * Pickup location data structure for display components.
 */
export interface PickupLocationData {
  latitude: number;
  longitude: number;
  address?: string;
  city?: string;
  zipCode?: string;
  isEstimated?: boolean;
}

export interface BookingDetails {
  // Trip (Exact Timestamp Architecture)
  id: number;
  status:
    | 'PENDING_APPROVAL'
    | 'ACTIVE'
    | 'DECLINED'
    | 'EXPIRED'
    | 'CANCELLED'
    | 'COMPLETED'
    | 'CHECK_IN_OPEN'
    | 'CHECK_IN_HOST_COMPLETE'
    | 'CHECK_IN_COMPLETE'
    | 'IN_TRIP'
    | 'CHECKOUT_OPEN'
    | 'CHECKOUT_GUEST_COMPLETE'
    | 'CHECKOUT_HOST_COMPLETE'
    | 'NO_SHOW_HOST'
    | 'NO_SHOW_GUEST'
    | 'EXPIRED_SYSTEM';
  startTime: string; // ISO-8601 datetime
  endTime: string; // ISO-8601 datetime
  totalPrice: number;
  insuranceType?: string;
  prepaidRefuel: boolean;
  cancellationPolicy: string;

  // Car
  carId: number;
  brand: string;
  model: string;
  year: number;
  licensePlate?: string;
  location: string;
  primaryImageUrl?: string;
  seats?: number;
  fuelType?: string;
  fuelConsumption?: number;
  transmissionType?: string;
  minRentalDays?: number;
  maxRentalDays?: number;

  // Host
  hostId: number;
  hostName: string;
  hostRating: number;
  hostTotalTrips: number;
  hostJoinedDate: string;
  hostAvatarUrl?: string;

  // ==================== PICKUP LOCATION (Phase 2.4) ====================

  /** Pickup location latitude (agreed at booking, or car home fallback) */
  pickupLatitude?: number;

  /** Pickup location longitude (agreed at booking, or car home fallback) */
  pickupLongitude?: number;

  /** Full street address for pickup location */
  pickupAddress?: string;

  /** City name for pickup location */
  pickupCity?: string;

  /** Postal code for pickup location */
  pickupZipCode?: string;

  /** True if pickup location is estimated (car home fallback for legacy bookings) */
  pickupLocationEstimated?: boolean;

  // ==================== LOCATION VARIANCE (Check-in Phase) ====================

  /** Distance in meters between agreed pickup and actual car location at check-in */
  pickupLocationVarianceMeters?: number;

  /** Variance status for UI badge display (NONE, WARNING, BLOCKING) */
  varianceStatus?: LocationVarianceStatus;

  // ==================== REVIEW STATUS ====================

  /** True if the current user has already submitted a review for this booking */
  reviewSubmitted?: boolean;

  // ==================== DELIVERY INFO ====================

  /** Calculated delivery distance in kilometers */
  deliveryDistanceKm?: number;

  /** Delivery fee in RSD */
  deliveryFeeCalculated?: number;
}
