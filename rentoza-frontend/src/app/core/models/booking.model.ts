import { Car } from './car.model';
import { User } from './user.model';

export interface Booking {
  id: string;
  car: Pick<Car, 'id' | 'make' | 'model' | 'imageUrl'>;
  renter: Pick<User, 'id' | 'firstName' | 'lastName'>;
  startDate: string;
  endDate: string;
  totalPrice: number;
  status: 'PENDING' | 'CONFIRMED' | 'CANCELLED' | 'COMPLETED';
  createdAt: string;
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
