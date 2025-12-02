export interface BookingDetails {
  // Trip (Exact Timestamp Architecture)
  id: number;
  status: 'PENDING_APPROVAL' | 'ACTIVE' | 'DECLINED' | 'EXPIRED' | 'CANCELLED' | 'COMPLETED';
  startTime: string;  // ISO-8601 datetime
  endTime: string;    // ISO-8601 datetime
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
}
