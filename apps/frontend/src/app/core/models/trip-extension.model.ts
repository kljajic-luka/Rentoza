export type TripExtensionStatus =
  | 'PENDING'
  | 'PAYMENT_PENDING'
  | 'APPROVED'
  | 'DECLINED'
  | 'EXPIRED'
  | 'CANCELLED';

export interface TripExtension {
  id: number;
  bookingId: number;
  originalEndDate: string;
  requestedEndDate: string;
  additionalDays: number;
  reason?: string | null;
  dailyRate?: number | null;
  additionalCost?: number | null;
  status: TripExtensionStatus;
  statusDisplay: string;
  paymentStatus?: string | null;
  paymentRedirectUrl?: string | null;
  paymentActionToken?: string | null;
  responseDeadline?: string | null;
  hostResponse?: string | null;
  respondedAt?: string | null;
  createdAt: string;
  vehicleName?: string | null;
  vehicleImageUrl?: string | null;
  guestId?: number | null;
  guestName?: string | null;
}

export interface TripExtensionHostResponse {
  response?: string;
}

export interface TripExtensionRequest {
  newEndDate: string;
  reason?: string;
}

export interface BookingReauthorizeRequest {
  paymentMethodId: string;
}

export interface BookingReauthorizeResponse {
  success: boolean;
  authorizationId?: string;
  status?: string;
  redirectUrl?: string;
  errorCode?: string;
  errorMessage?: string;
}