import { PayoutLifecycleStatus } from '../payment/payment-status.mapper';

/**
 * Per-booking payout status from the backend.
 *
 * Served by GET /api/owner/payouts (OwnerController).
 */
export interface BookingPayoutStatus {
  bookingId: number;
  carBrand: string;
  carModel: string;
  guestName: string;
  tripStartTime: string;
  tripEndTime: string;
  payoutStatus: PayoutLifecycleStatus;
  /** ISO-8601 datetime when payout becomes eligible (dispute hold end). */
  eligibleAt: string | null;
  tripAmount: number;
  platformFee: number;
  hostPayoutAmount: number;
  attemptCount: number;
  maxAttempts: number;
  /** ISO-8601 datetime for next retry attempt (if FAILED). */
  nextRetryAt: string | null;
  lastError: string | null;
}

/**
 * Response shape from GET /api/owner/payouts.
 */
export interface OwnerPayoutsResponse {
  payouts: BookingPayoutStatus[];
}
