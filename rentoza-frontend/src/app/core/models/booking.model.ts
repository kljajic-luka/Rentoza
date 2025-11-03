import { Car } from './car.model';
import { User } from './user.model';

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
  status: 'PENDING' | 'CONFIRMED' | 'ACTIVE' | 'CANCELLED' | 'COMPLETED';
  createdAt: string;
  hasOwnerReview?: boolean;
}

export interface BookingRequest {
  carId: string;
  startDate: string;
  endDate: string;
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
}
