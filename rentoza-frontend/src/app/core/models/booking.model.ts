import { Car } from './car.model';
import { User } from './user.model';

export type PickupTimeWindow = 'MORNING' | 'AFTERNOON' | 'EVENING' | 'EXACT';

export type BookingStatus = 'PENDING_APPROVAL' | 'ACTIVE' | 'DECLINED' | 'EXPIRED' | 'CANCELLED' | 'COMPLETED';

export interface Booking {
  id: string | number;
  car: {
    id: string | number;
    make: string;
    model: string;
    imageUrl?: string;
  };
  renter: {
    id: string | number;
    firstName?: string;
    lastName?: string;
  };
  startDate: string;
  endDate: string;
  totalPrice: number;
  status: BookingStatus;
  createdAt: string;
  hasOwnerReview?: boolean;
  pickupTimeWindow?: PickupTimeWindow; // Phase 2.2
  pickupTime?: string; // HH:mm format, only for EXACT
  // Host Approval Fields (Phase 3)
  approvedBy?: number;
  approvedAt?: string;
  declinedBy?: number;
  declinedAt?: string;
  declineReason?: string;
  decisionDeadlineAt?: string;
  version?: number; // Optimistic locking version
}

export interface BookingRequest {
  carId: string;
  startDate: string;
  endDate: string;
  insuranceType?: string; // BASIC, STANDARD, PREMIUM
  prepaidRefuel?: boolean;
  pickupTimeWindow?: PickupTimeWindow; // Phase 2.2: MORNING | AFTERNOON | EVENING | EXACT
  pickupTime?: string; // Phase 2.2: HH:mm format, required only if pickupTimeWindow === 'EXACT'
}

export interface UserBooking {
  id: number;
  carId: number;
  carBrand: string;
  carModel: string;
  carYear: number;
  carImageUrl: string | null;
  carLocation: string;
  carPricePerDay: number;
  startDate: string;
  endDate: string;
  totalPrice: number;
  status: string;
  hasReview: boolean;
  reviewRating: number | null;
  reviewComment: string | null;
  insuranceType?: string; // BASIC, STANDARD, PREMIUM
  prepaidRefuel?: boolean;
  pickupTimeWindow?: PickupTimeWindow; // Phase 2.2
  pickupTime?: string; // Phase 2.2: HH:mm format
}

/**
 * Public-safe DTO for calendar availability display.
 *
 * Purpose:
 * - Minimal booking information for calendar UI (shows which dates are booked)
 * - No PII exposure (no renter, owner, or pricing information)
 * - Used by renters/guests to see unavailable dates
 *
 * Backend Endpoint:
 * - GET /api/bookings/car/{carId}/public
 * - @PreAuthorize("permitAll()") - accessible to all users
 */
export interface BookingSlotDto {
  carId: number;
  startDate: string; // ISO date string
  endDate: string; // ISO date string
}
