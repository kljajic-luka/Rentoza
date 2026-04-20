import { Car } from './car.model';
import { User } from './user.model';
import {
  calculateBillablePeriodsSerbia,
  formatTripDurationSerbia,
  getSerbiaTimeHHmm,
  toSerbiaCalendarDate,
} from '../utils/serbia-time.util';

/**
 * Booking status enum values.
 *
 * <h2>Exact Timestamp Architecture</h2>
 * Statuses remain the same, but timing-related behavior
 * is now based on exact timestamps instead of date + time windows.
 */
export type BookingStatus =
  | 'PENDING_APPROVAL'
  | 'ACTIVE'
  | 'DECLINED'
  | 'EXPIRED'
  | 'EXPIRED_SYSTEM' // System auto-expired due to host inactivity
  | 'CANCELLED'
  | 'CANCELLATION_PENDING_SETTLEMENT'
  | 'COMPLETED'
  // Check-in workflow statuses
  | 'CHECK_IN_OPEN'
  | 'CHECK_IN_HOST_COMPLETE'
  | 'CHECK_IN_COMPLETE'
  | 'IN_TRIP'
  | 'NO_SHOW_HOST'
  | 'NO_SHOW_GUEST'
  // Checkout workflow statuses
  | 'CHECKOUT_OPEN'
  | 'CHECKOUT_GUEST_COMPLETE'
  | 'CHECKOUT_HOST_COMPLETE'
  | 'CHECKOUT_DAMAGE_DISPUTE';

export type CheckInStatus =
  | 'NOT_STARTED'
  | 'CHECK_IN_OPEN'
  | 'HOST_SUBMITTED'
  | 'GUEST_ACKNOWLEDGED'
  | 'HANDSHAKE_PENDING'
  | 'COMPLETED'
  | 'DISPUTED';

export interface AgreementSummary {
  workflowStatus:
    | 'LEGACY'
    | 'AGREEMENT_PENDING_BOTH'
    | 'AGREEMENT_PENDING_OWNER'
    | 'AGREEMENT_PENDING_RENTER'
    | 'AGREEMENT_COMPLETE'
    | 'AGREEMENT_EXPIRED_OWNER_BREACH'
    | 'AGREEMENT_EXPIRED_RENTER_BREACH'
    | 'AGREEMENT_EXPIRED_BOTH_PARTIES';
  ownerAccepted: boolean;
  renterAccepted: boolean;
  currentActorNeedsAcceptance: boolean;
  currentActorCanProceedToCheckIn: boolean;
  legacyBooking: boolean;
  acceptanceDeadlineAt: string | null;
  urgencyLevel: 'NONE' | 'NORMAL' | 'URGENT' | 'OVERDUE';
  recommendedPrimaryAction:
    | 'ACCEPT_RENTAL_AGREEMENT'
    | 'OPEN_CHECK_IN'
    | 'WAIT_FOR_OTHER_PARTY'
    | 'VIEW_BOOKING_DETAILS';
}

/**
 * Booking interface.
 *
 * <h2>Exact Timestamp Architecture</h2>
 * Uses precise start/end timestamps instead of date + time window.
 * All times are in Europe/Belgrade timezone.
 */
export interface Booking {
  id: string | number;
  car: {
    id: string | number;
    brand: string;
    model: string;
    year?: number;
    imageUrl?: string;
  };
  renter: {
    id: string | number;
    firstName?: string;
    lastName?: string;
    email?: string;
    phone?: string;
    avatarUrl?: string;
  };
  /**
   * Exact trip start timestamp.
   * Format: ISO-8601 LocalDateTime (e.g., "2025-10-10T10:00:00")
   */
  startTime: string;
  /**
   * Exact trip end timestamp.
   * Format: ISO-8601 LocalDateTime (e.g., "2025-10-12T10:00:00")
   */
  endTime: string;
  totalPrice: number;
  status: BookingStatus;
  createdAt: string;
  hasOwnerReview?: boolean;
  // Host Approval Fields
  approvedBy?: number;
  approvedAt?: string;
  declinedBy?: number;
  declinedAt?: string;
  declineReason?: string;
  decisionDeadlineAt?: string;
  version?: number; // Optimistic locking version
  agreementSummary?: AgreementSummary | null;
  // Check-in fields
  checkInStatus?: CheckInStatus;
  checkInOpenAt?: string;
  checkInCompletedAt?: string;
}

/**
 * Booking request interface.
 *
 * <h2>Exact Timestamp Architecture</h2>
 * Uses precise start/end timestamps.
 * Times should be on 30-minute boundaries.
 * Minimum duration: 24 hours.
 */
export interface BookingRequest {
  carId: string;
  /**
   * Exact trip start timestamp.
   * Format: ISO-8601 LocalDateTime (e.g., "2025-10-10T10:00:00")
   */
  startTime: string;
  /**
   * Exact trip end timestamp.
   * Format: ISO-8601 LocalDateTime (e.g., "2025-10-12T10:00:00")
   */
  endTime: string;
  insuranceType?: string; // BASIC, STANDARD, PREMIUM
  prepaidRefuel?: boolean;

  // Driver information
  driverName?: string;
  driverSurname?: string;
  driverAge?: number;
  driverPhone?: string;

  // Pickup location (Phase 2.4 - Geospatial)
  /** Pickup latitude (WGS84), must be within Serbia bounds (42.2-46.2) */
  pickupLatitude?: number;
  /** Pickup longitude (WGS84), must be within Serbia bounds (18.8-23.0) */
  pickupLongitude?: number;
  /** Human-readable pickup address (reverse-geocoded or user-provided) */
  pickupAddress?: string;
  /** City name (e.g., "Belgrade", "Novi Sad") */
  pickupCity?: string;
  /** Postal code (e.g., "11000") */
  pickupZipCode?: string;
  /** Whether guest requests delivery to a custom location (triggers fee calculation) */
  deliveryRequested?: boolean;

  // Payment fields (used during booking creation; not sent for validateBooking)
  /** Tokenized payment method ID. Use 'mock_default' in dev/staging MOCK mode. */
  paymentMethodId?: string;
  /** UUID generated per-submission to prevent duplicate charges on network retry. */
  idempotencyKey?: string;
}

/**
 * Redirect envelope returned by createBooking when 3DS/SCA is required.
 * The booking is already persisted; the webhook confirms payment on return.
 */
export interface BookingCreateRedirectResponse {
  redirectRequired: true;
  redirectUrl: string;
  booking?: Booking;
}

/**
 * Union of the two possible shapes returned by POST /bookings.
 * - Normal flow: Booking DTO
 * - 3DS/SCA flow: BookingCreateRedirectResponse
 */
export type BookingCreateResponse = Booking | BookingCreateRedirectResponse;

/** Narrows a BookingCreateResponse to the redirect envelope. */
export function isRedirectResponse(r: BookingCreateResponse): r is BookingCreateRedirectResponse {
  return (r as BookingCreateRedirectResponse).redirectRequired === true;
}

/**
 * User's booking history entry.
 */
export interface UserBooking {
  id: number;
  carId: number;
  carBrand: string;
  carModel: string;
  carYear: number;
  carImageUrl: string | null;
  carLocation: string;
  carPricePerDay: number;
  /**
   * Exact trip start timestamp.
   */
  startTime: string;
  /**
   * Exact trip end timestamp.
   */
  endTime: string;
  totalPrice: number;
  status: string;
  decisionDeadlineAt?: string;
  approvedAt?: string;
  declinedAt?: string;
  declineReason?: string;
  agreementSummary?: AgreementSummary | null;
  hasReview: boolean;
  reviewRating: number | null;
  reviewComment: string | null;
  insuranceType?: string;
  prepaidRefuel?: boolean;
}

/**
 * Public-safe DTO for calendar availability display.
 *
 * Purpose:
 * - Minimal booking information for calendar UI (shows which times are booked)
 * - No PII exposure (no renter, owner, or pricing information)
 * - Used by renters/guests to see unavailable dates
 *
 * Calendar Display:
 * Since we use full-day blocking for calendar display, the frontend should
 * gray out entire days if any hours within that day are booked.
 */
export interface BookingSlotDto {
  carId: number;
  /**
   * Exact trip start timestamp.
   */
  startTime: string;
  /**
   * Exact trip end timestamp.
   */
  endTime: string;
}

/**
 * Time slot option for the datetime picker.
 */
export interface TimeSlot {
  value: string; // HH:mm format
  label: string; // Display label (e.g., "10:00", "10:30")
}

/**
 * Generate time slots in 30-minute intervals.
 */
export function generateTimeSlots(): TimeSlot[] {
  const slots: TimeSlot[] = [];
  for (let hour = 0; hour < 24; hour++) {
    for (const minute of [0, 30]) {
      const hh = hour.toString().padStart(2, '0');
      const mm = minute.toString().padStart(2, '0');
      const value = `${hh}:${mm}`;
      slots.push({ value, label: value });
    }
  }
  return slots;
}

/**
 * Combine date and time into ISO-8601 LocalDateTime string.
 */
export function combineDateTime(date: Date, time: string): string {
  const year = date.getFullYear();
  const month = (date.getMonth() + 1).toString().padStart(2, '0');
  const day = date.getDate().toString().padStart(2, '0');
  return `${year}-${month}-${day}T${time}:00`;
}

/**
 * Parse ISO-8601 LocalDateTime string to Date and time.
 */
export function parseDateTime(dateTimeStr: string): { date: Date; time: string } {
  return {
    date: toSerbiaCalendarDate(dateTimeStr),
    time: getSerbiaTimeHHmm(dateTimeStr),
  };
}

/**
 * Format duration in hours/days for display.
 */
export function formatDuration(startTime: string, endTime: string): string {
  return formatTripDurationSerbia(startTime, endTime);
}

/**
 * Calculate rental periods (24-hour blocks) for pricing.
 */
export function calculatePeriods(startTime: string, endTime: string): number {
  return calculateBillablePeriodsSerbia(startTime, endTime);
}
