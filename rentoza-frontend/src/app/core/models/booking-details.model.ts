export interface BookingDetails {
  // Trip
  id: number;
  status: 'PENDING_APPROVAL' | 'ACTIVE' | 'DECLINED' | 'EXPIRED' | 'CANCELLED' | 'COMPLETED';
  startDate: string;
  endDate: string;
  pickupTime?: string;
  pickupTimeWindow?: string;
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
