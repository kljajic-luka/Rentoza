import { Car } from './car.model';
import { User } from './user.model';

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
  | 'CHECKOUT_HOST_COMPLETE';

export type CheckInStatus =
  | 'NOT_STARTED'
  | 'CHECK_IN_OPEN'
  | 'HOST_SUBMITTED'
  | 'GUEST_ACKNOWLEDGED'
  | 'HANDSHAKE_PENDING'
  | 'COMPLETED'
  | 'DISPUTED';

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
  pickupLatitude?: number;
  pickupLongitude?: number;
  pickupAddress?: string;
  isCarLocationPickup?: boolean;
  deliveryFee?: number;
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
  const dt = new Date(dateTimeStr);
  const time = `${dt.getHours().toString().padStart(2, '0')}:${dt
    .getMinutes()
    .toString()
    .padStart(2, '0')}`;
  return { date: dt, time };
}

/**
 * Format duration in hours/days for display.
 */
export function formatDuration(startTime: string, endTime: string): string {
  const start = new Date(startTime);
  const end = new Date(endTime);
  const hours = Math.round((end.getTime() - start.getTime()) / (1000 * 60 * 60));

  if (hours < 24) {
    return `${hours} sat${hours === 1 ? '' : hours < 5 ? 'a' : 'i'}`;
  }

  const days = Math.ceil(hours / 24);
  return `${days} dan${days === 1 ? '' : days < 5 ? 'a' : 'a'}`;
}

/**
 * Calculate rental periods (24-hour blocks) for pricing.
 */
export function calculatePeriods(startTime: string, endTime: string): number {
  const start = new Date(startTime);
  const end = new Date(endTime);
  const hours = (end.getTime() - start.getTime()) / (1000 * 60 * 60);
  return Math.max(1, Math.ceil(hours / 24));
}
