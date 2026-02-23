import { Feature, TransmissionType } from './car.model';

/**
 * Car search criteria - mirrors backend CarSearchCriteria
 */
export interface CarSearchCriteria {
  // Price filtering
  minPrice?: number;
  maxPrice?: number;

  // Brand filtering
  make?: string;
  model?: string;

  // Year filtering
  minYear?: number;
  maxYear?: number;

  // Seats filtering
  minSeats?: number;

  // Transmission filtering
  transmission?: TransmissionType;

  // Features filtering
  features?: Feature[];

  // Free-text query (canonical param: q, legacy alias: search)
  // OR-matched across brand, model, location, description
  q?: string;

  // Pagination and sorting
  page?: number;
  size?: number;
  sort?: string; // e.g., "pricePerDay,asc" or "year,desc"
}

/**
 * Geospatial search parameters for location-based filtering.
 * Used when coordinates are available from geocoding.
 */
export interface GeospatialSearchParams {
  latitude: number;
  longitude: number;
  radiusKm: number;
}

/**
 * Unified availability search parameters combining:
 * - Time-based availability (location + date/time range)
 * - Geospatial coordinates (latitude, longitude, radius)
 * - All filter criteria (price, make, transmission, features, etc.)
 * - Pagination and sorting
 *
 * This is the single source of truth for availability mode searches.
 * All fields are optional except for the core availability params.
 */
export interface AvailabilitySearchParams {
  // ========== Core Availability Params (Required) ==========
  /** Location string (city/region for display and fallback) */
  location: string;
  /** Rental start timestamp (ISO 8601: YYYY-MM-DDTHH:mm:ss) */
  startTime: string;
  /** Rental end timestamp (ISO 8601: YYYY-MM-DDTHH:mm:ss) */
  endTime: string;

  // ========== Geospatial Params (Optional - when geocoded) ==========
  /** Center point latitude (from geocoding) */
  latitude?: number;
  /** Center point longitude (from geocoding) */
  longitude?: number;
  /** Search radius in kilometers (default: 20km) */
  radiusKm?: number;

  // ========== Filter Params (Optional - from CarSearchCriteria) ==========
  /** Minimum price per day */
  minPrice?: number;
  /** Maximum price per day */
  maxPrice?: number;
  /** Car make/brand filter */
  make?: string;
  /** Car model filter */
  model?: string;
  /** Minimum year filter */
  minYear?: number;
  /** Maximum year filter */
  maxYear?: number;
  /** Minimum seats filter */
  minSeats?: number;
  /** Transmission type filter */
  transmission?: TransmissionType;
  /** Required features filter */
  features?: Feature[];
  /** Free-text query (canonical param: q, legacy alias: search) */
  q?: string;

  // ========== Pagination Params ==========
  /** Page number (0-indexed) */
  page: number;
  /** Page size */
  size: number;
  /** Sort order (e.g., "pricePerDay,asc") */
  sort?: string;
}

/**
 * Helper to merge CarSearchCriteria into AvailabilitySearchParams.
 * Preserves existing availability params while overlaying filter changes.
 */
export function mergeFiltersIntoAvailabilityParams(
  base: AvailabilitySearchParams,
  filters: CarSearchCriteria,
): AvailabilitySearchParams {
  return {
    // Preserve core availability params
    location: base.location,
    startTime: base.startTime,
    endTime: base.endTime,

    // Preserve geospatial params
    latitude: base.latitude,
    longitude: base.longitude,
    radiusKm: base.radiusKm,

    // Overlay filter params (new values override base)
    minPrice: filters.minPrice,
    maxPrice: filters.maxPrice,
    make: filters.make,
    model: filters.model,
    minYear: filters.minYear,
    maxYear: filters.maxYear,
    minSeats: filters.minSeats,
    transmission: filters.transmission,
    features: filters.features ? [...filters.features] : undefined,
    q: filters.q?.trim() || base.q || undefined,

    // Pagination: reset to page 0 when filters change, preserve size
    page: 0,
    size: filters.size ?? base.size,
    sort: filters.sort ?? base.sort,
  };
}

/**
 * Extract CarSearchCriteria from AvailabilitySearchParams.
 * Used when filters component needs current filter state.
 */
export function extractFiltersFromAvailabilityParams(
  params: AvailabilitySearchParams,
): CarSearchCriteria {
  const criteria: CarSearchCriteria = {
    page: params.page,
    size: params.size,
  };

  if (params.minPrice !== undefined) criteria.minPrice = params.minPrice;
  if (params.maxPrice !== undefined) criteria.maxPrice = params.maxPrice;
  if (params.make) criteria.make = params.make;
  if (params.model) criteria.model = params.model;
  if (params.minYear !== undefined) criteria.minYear = params.minYear;
  if (params.maxYear !== undefined) criteria.maxYear = params.maxYear;
  if (params.minSeats !== undefined) criteria.minSeats = params.minSeats;
  if (params.transmission) criteria.transmission = params.transmission;
  if (params.features && params.features.length > 0) {
    criteria.features = [...params.features];
  }
  if (params.sort) criteria.sort = params.sort;
  if (params.q?.trim()) criteria.q = params.q.trim();

  return criteria;
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
  RATING_DESC = 'rating,desc',
  RATING_ASC = 'rating,asc',
}
