import { Review } from './review.model';

/**
 * Fuel type enumeration
 */
export enum FuelType {
  BENZIN = 'BENZIN', // Gasoline/Petrol
  DIZEL = 'DIZEL', // Diesel
  ELEKTRIČNI = 'ELEKTRIČNI', // Electric
  HIBRID = 'HIBRID', // Hybrid
  PLUG_IN_HIBRID = 'PLUG_IN_HIBRID', // Plug-in Hybrid
}

/**
 * Transmission type enumeration
 */
export enum TransmissionType {
  MANUAL = 'MANUAL', // Manual
  AUTOMATIC = 'AUTOMATIC', // Automatic
}

/**
 * Car features enumeration
 */
export enum Feature {
  // Safety
  ABS = 'ABS',
  AIRBAG = 'AIRBAG',
  PARKING_SENSORS = 'PARKING_SENSORS',
  REVERSE_CAMERA = 'REVERSE_CAMERA',
  BLIND_SPOT_MONITOR = 'BLIND_SPOT_MONITOR',
  LANE_ASSIST = 'LANE_ASSIST',
  CRUISE_CONTROL = 'CRUISE_CONTROL',
  ADAPTIVE_CRUISE = 'ADAPTIVE_CRUISE',

  // Connectivity
  BLUETOOTH = 'BLUETOOTH',
  USB = 'USB',
  ANDROID_AUTO = 'ANDROID_AUTO',
  APPLE_CARPLAY = 'APPLE_CARPLAY',
  NAVIGATION = 'NAVIGATION',
  WIFI = 'WIFI',

  // Comfort
  AIR_CONDITIONING = 'AIR_CONDITIONING',
  CLIMATE_CONTROL = 'CLIMATE_CONTROL',
  HEATED_SEATS = 'HEATED_SEATS',
  LEATHER_SEATS = 'LEATHER_SEATS',
  SUNROOF = 'SUNROOF',
  PANORAMIC_ROOF = 'PANORAMIC_ROOF',
  KEYLESS_ENTRY = 'KEYLESS_ENTRY',
  PUSH_START = 'PUSH_START',
  ELECTRIC_WINDOWS = 'ELECTRIC_WINDOWS',
  POWER_STEERING = 'POWER_STEERING',

  // Additional
  ROOF_RACK = 'ROOF_RACK',
  TOW_HITCH = 'TOW_HITCH',
  ALLOY_WHEELS = 'ALLOY_WHEELS',
  LED_LIGHTS = 'LED_LIGHTS',
  FOG_LIGHTS = 'FOG_LIGHTS',
}

/**
 * Cancellation policy enumeration
 */
export enum CancellationPolicy {
  FLEXIBLE = 'FLEXIBLE',
  MODERATE = 'MODERATE',
  STRICT = 'STRICT',
  NON_REFUNDABLE = 'NON_REFUNDABLE',
}

/**
 * Main Car interface with all production-ready fields
 */
export interface Car {
  id: string;
  make: string; // canonical brand field used across frontend
  brand?: string; // retained from backend for compatibility/fallback
  model: string;
  year: number;
  licensePlate?: string; // Added licensePlate
  pricePerDay: number;
  location: string;
  description?: string;
  imageUrl?: string;
  available?: boolean;
  rating?: number;
  reviews?: Review[];

  // Geospatial location fields (Phase 2.4)
  locationLatitude?: number;
  locationLongitude?: number;
  locationAddress?: string;
  locationCity?: string;
  locationZipCode?: string;

  /**
   * Privacy flag: true if viewer has access to exact location.
   * - true: Owner or active booker → show exact pin on map
   * - false: Public view → show fuzzy circle (~500m radius)
   */
  isExactLocation?: boolean;

  // Delivery options (Phase 2.4)
  deliveryAvailable?: boolean;
  deliveryMaxRadiusKm?: number;
  deliveryFeePerKm?: number;

  // New production-ready fields
  seats?: number;
  fuelType?: FuelType;
  fuelConsumption?: number; // liters per 100km
  transmissionType?: TransmissionType;
  features?: Feature[];
  addOns?: string[];
  cancellationPolicy?: CancellationPolicy;
  minRentalDays?: number;
  maxRentalDays?: number;
  imageUrls?: string[];

  // Owner information (Privacy Safe)
  ownerId?: number;
  ownerFirstName?: string;
  ownerLastInitial?: string;
  ownerAvatarUrl?: string;
  ownerJoinDate?: string;
  ownerRating?: number;
  ownerTripCount?: number;

  // ✅ ADDED FOR APPROVAL WORKFLOW
  approvalStatus?: ApprovalStatus;
  rejectionReason?: string;
  approvedAt?: string;
  approvedBy?: string;
}

/**
 * Approval status for car listings
 */
export enum ApprovalStatus {
  PENDING = 'PENDING',
  APPROVED = 'APPROVED',
  REJECTED = 'REJECTED',
  SUSPENDED = 'SUSPENDED',
}

export interface CarSummary extends Car {
  availableFrom?: string;
  availableTo?: string;
}

/**
 * Unavailable time range for a car.
 * Used by the availability endpoint to communicate blocked periods to the frontend calendar.
 */
export interface UnavailableRange {
  /** Start timestamp of the unavailable period (ISO-8601) */
  start: string;
  /** End timestamp of the unavailable period (ISO-8601) */
  end: string;
  /** The reason why this period is unavailable */
  reason: 'BOOKING' | 'BLOCKED_DATE' | 'GAP_TOO_SMALL';
}

/**
 * Default rental rules (Serbian)
 */
export const CAR_RENTAL_RULES = [
  'Zabranjeno pušenje',
  'Vozilo vratiti sa punim rezervoarom',
  'Bez vožnje van puta',
  'Održavajte vozilo čistim',
  'Prijavite eventualne štete',
  'Vratite na vreme',
] as const;
