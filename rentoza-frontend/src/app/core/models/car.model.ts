import { Review } from './review.model';

export interface Car {
  id: string;
  make: string;
  model: string;
  year: number;
  pricePerDay: number;
  location: string;
  description?: string;
  imageUrl?: string;
  rating?: number;
  reviews?: Review[];
}

export interface CarSummary extends Car {
  availableFrom?: string;
  availableTo?: string;
}
