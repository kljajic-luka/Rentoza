import { Feature, TransmissionType } from './car.model';

/**
 * Car search criteria - mirrors backend CarSearchCriteria
 */
export interface CarSearchCriteria {
  // Price filtering
  minPrice?: number;
  maxPrice?: number;

  // Vehicle type/brand filtering
  vehicleType?: string;
  make?: string;
  model?: string;

  // Year filtering
  minYear?: number;
  maxYear?: number;

  // Location filtering
  location?: string;

  // Seats filtering
  minSeats?: number;

  // Transmission filtering
  transmission?: TransmissionType;

  // Features filtering
  features?: Feature[];

  // Pagination and sorting
  page?: number;
  size?: number;
  sort?: string; // e.g., "pricePerDay,asc" or "year,desc"
}

/**
 * Paginated response from backend
 */
export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

/**
 * Sort options for car search
 */
export enum CarSortOption {
  RELEVANCE = 'relevance',
  PRICE_ASC = 'pricePerDay,asc',
  PRICE_DESC = 'pricePerDay,desc',
  YEAR_DESC = 'year,desc',
  YEAR_ASC = 'year,asc',
}
