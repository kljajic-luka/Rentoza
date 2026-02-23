import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map, shareReplay } from 'rxjs';

import { environment } from '@environments/environment';
import { Car, UnavailableRange } from '@core/models/car.model';
import { Review } from '@core/models/review.model';
import {
  AvailabilitySearchParams,
  CarSearchCriteria,
  PagedResponse,
} from '@core/models/car-search.model';
import { isWithinRadius } from '@core/utils/distance.util';
import { normalizeSearchString } from '@core/utils/string-normalization.util';
import { clearHttpCacheByPattern } from '@core/interceptors/http-cache.interceptor';
import { getPrimaryImageUrl, normalizeMediaUrlArray } from '@shared/utils/media-url.util';

/**
 * Backend Car DTO interface (uses 'brand' instead of 'make')
 */
interface BackendCarDTO {
  id: string;
  brand: string; // Backend uses 'brand'
  model: string;
  year: number;
  pricePerDay: number;
  location: string;
  description?: string;
  imageUrl?: string;
  available?: boolean;
  seats?: number;
  fuelType?: string;
  fuelConsumption?: number;
  transmissionType?: string;
  features?: string[];
  addOns?: string[];
  cancellationPolicy?: string;
  minRentalDays?: number;
  maxRentalDays?: number;
  imageUrls?: string[];
  ownerFullName?: string;
  ownerEmail?: string;
}

@Injectable({ providedIn: 'root' })
export class CarService {
  private readonly baseUrl = `${environment.baseApiUrl}/cars`;

  // Cache for search results to avoid duplicate requests
  private searchCache = new Map<string, Observable<PagedResponse<Car>>>();
  private readonly CACHE_SIZE = 20; // Keep last 20 search queries

  constructor(private readonly http: HttpClient) {}

  /**
   * Map backend DTO to frontend Car model
   * Backend uses 'brand', frontend uses 'make'
   */
  private mapBackendCarToFrontend(backendCar: any): Car {
    const { brand, make, model, ...rest } = backendCar;
    // Handle swapped brand/model responses defensively: prefer brand, fallback to model
    const resolvedBrand = brand || make || model || '';
    const resolvedModel = model || brand || '';

    const normalizedImageUrls = normalizeMediaUrlArray(rest.imageUrls);
    const primaryImageUrl = getPrimaryImageUrl({
      imageUrl: rest.imageUrl,
      imageUrls: normalizedImageUrls,
    });

    return {
      ...rest,
      make: resolvedBrand,
      brand: resolvedBrand,
      model: resolvedModel,
      imageUrls: normalizedImageUrls.length > 0 ? normalizedImageUrls : undefined,
      imageUrl: primaryImageUrl ?? undefined,
    } as Car;
  }

  getCars(): Observable<Car[]> {
    return this.http
      .get<any[]>(this.baseUrl)
      .pipe(map((cars) => cars.map((car) => this.mapBackendCarToFrontend(car))));
  }

  /**
   * Search cars by availability (location + time range + geospatial + filters).
   * Uses exact timestamp architecture for precise availability filtering.
   *
   * UPGRADED: Now accepts unified AvailabilitySearchParams DTO that includes:
   * - Core availability: location, startTime, endTime
   * - Geospatial: latitude, longitude, radiusKm (optional)
   * - Filters: minPrice, maxPrice, make, model, year, seats, transmission, features
   * - Pagination: page, size, sort
   *
   * When geospatial coordinates are provided, backend uses spatial index for
   * proximity-based search. Otherwise, falls back to location-string search.
   *
   * @param params Unified availability search parameters
   * @returns Paginated response with available cars
   */
  searchAvailableCars(params: AvailabilitySearchParams): Observable<PagedResponse<Car>> {
    let httpParams = new HttpParams()
      .set('location', params.location)
      .set('startTime', params.startTime)
      .set('endTime', params.endTime)
      .set('page', params.page.toString())
      .set('size', params.size.toString());

    // Geospatial params (when coordinates available from geocoding)
    if (params.latitude !== undefined && params.longitude !== undefined) {
      httpParams = httpParams
        .set('latitude', params.latitude.toString())
        .set('longitude', params.longitude.toString());

      if (params.radiusKm !== undefined) {
        httpParams = httpParams.set('radiusKm', params.radiusKm.toString());
      }
    }

    // Filter params
    if (params.minPrice !== undefined) {
      httpParams = httpParams.set('minPrice', params.minPrice.toString());
    }
    if (params.maxPrice !== undefined) {
      httpParams = httpParams.set('maxPrice', params.maxPrice.toString());
    }
    if (params.make) {
      httpParams = httpParams.set('make', normalizeSearchString(params.make));
    }
    if (params.model) {
      httpParams = httpParams.set('model', normalizeSearchString(params.model));
    }
    if (params.minYear !== undefined) {
      httpParams = httpParams.set('minYear', params.minYear.toString());
    }
    if (params.maxYear !== undefined) {
      httpParams = httpParams.set('maxYear', params.maxYear.toString());
    }
    if (params.minSeats !== undefined) {
      httpParams = httpParams.set('minSeats', params.minSeats.toString());
    }
    if (params.transmission) {
      httpParams = httpParams.set('transmission', params.transmission);
    }
    if (params.features && params.features.length > 0) {
      httpParams = httpParams.set('features', params.features.join(','));
    }
    if (params.q?.trim()) {
      httpParams = httpParams.set('q', normalizeSearchString(params.q));
    }
    if (params.sort) {
      httpParams = httpParams.set('sort', params.sort);
    }

    return this.http.get<any>(`${this.baseUrl}/availability-search`, { params: httpParams }).pipe(
      map((response) => ({
        content: response.content.map((car: any) => this.mapBackendCarToFrontend(car)),
        totalElements: response.totalElements,
        totalPages: response.totalPages,
        currentPage: response.currentPage,
        pageSize: response.pageSize,
        hasNext: response.hasNext,
        hasPrevious: response.hasPrevious,
      }))
    );
  }

  /**
   * @deprecated Use searchAvailableCars(AvailabilitySearchParams) instead.
   * Legacy overload kept for backward compatibility during migration.
   */
  searchAvailableCarsLegacy(
    location: string,
    startDateTime: string,
    startTimeOfDay: string,
    endDateTime: string,
    endTimeOfDay: string,
    page: number = 0,
    size: number = 20,
    sort?: string
  ): Observable<PagedResponse<Car>> {
    // Combine date + time if needed, or use as-is if already ISO timestamp
    const startTime = this.toISOTimestamp(startDateTime, startTimeOfDay);
    const endTime = this.toISOTimestamp(endDateTime, endTimeOfDay);

    const params: AvailabilitySearchParams = {
      location,
      startTime,
      endTime,
      page,
      size,
      sort,
    };

    return this.searchAvailableCars(params);
  }

  /**
   * Get unavailable time ranges for a specific car.
   * Used by the booking calendar to disable invalid date/time selections.
   *
   * @param carId Car ID
   * @param start Optional start of query window (ISO-8601 datetime, default: now)
   * @param end Optional end of query window (ISO-8601 datetime, default: start + 1 year)
   * @returns Observable of unavailable ranges
   */
  getCarAvailability(
    carId: number,
    start?: string, // ISO-8601 datetime
    end?: string // ISO-8601 datetime
  ): Observable<UnavailableRange[]> {
    let params = new HttpParams();
    if (start) params = params.set('start', start);
    if (end) params = params.set('end', end);

    return this.http.get<UnavailableRange[]>(`${this.baseUrl}/${carId}/availability`, { params });
  }

  /**
   * Convert date string + time to ISO timestamp, or return as-is if already ISO.
   */
  private toISOTimestamp(dateOrTimestamp: string, time: string): string {
    // If dateOrTimestamp is already an ISO timestamp, return it
    if (dateOrTimestamp.includes('T')) {
      return dateOrTimestamp;
    }
    // If time is already an ISO timestamp (and dateOrTimestamp is empty), return time
    if (!dateOrTimestamp && time.includes('T')) {
      return time;
    }
    // Otherwise, combine date + time
    if (!dateOrTimestamp) {
      throw new Error('Both dateOrTimestamp and time cannot be empty');
    }
    return `${dateOrTimestamp}T${time}:00`;
  }

  /**
   * Search cars with filters, sorting, and pagination
   * Includes request deduplication and caching for performance
   * @param criteria Search criteria
   * @returns Paginated response with cars
   */
  searchCars(criteria: CarSearchCriteria): Observable<PagedResponse<Car>> {
    // Create cache key from criteria
    const cacheKey = JSON.stringify(this.cleanCriteria(criteria));

    // Check cache first
    if (this.searchCache.has(cacheKey)) {
      return this.searchCache.get(cacheKey)!;
    }

    // Clean criteria - remove empty/null/default values to minimize payload
    const cleanedCriteria = this.cleanCriteria(criteria);
    let params = new HttpParams();

    // Add only non-null/undefined criteria to params
    // Apply normalization to text-based filters
    if (cleanedCriteria.minPrice !== undefined) {
      params = params.set('minPrice', cleanedCriteria.minPrice.toString());
    }
    if (cleanedCriteria.maxPrice !== undefined) {
      params = params.set('maxPrice', cleanedCriteria.maxPrice.toString());
    }
    if (cleanedCriteria.make) {
      params = params.set('make', normalizeSearchString(cleanedCriteria.make));
    }
    if (cleanedCriteria.model) {
      params = params.set('model', normalizeSearchString(cleanedCriteria.model));
    }
    if (cleanedCriteria.minYear !== undefined) {
      params = params.set('minYear', cleanedCriteria.minYear.toString());
    }
    if (cleanedCriteria.maxYear !== undefined) {
      params = params.set('maxYear', cleanedCriteria.maxYear.toString());
    }
    if (cleanedCriteria.minSeats !== undefined) {
      params = params.set('minSeats', cleanedCriteria.minSeats.toString());
    }
    if (cleanedCriteria.transmission) {
      params = params.set('transmission', cleanedCriteria.transmission);
    }
    if (cleanedCriteria.features && cleanedCriteria.features.length > 0) {
      // Send features as comma-separated string
      params = params.set('features', cleanedCriteria.features.join(','));
    }
    if (cleanedCriteria.q?.trim()) {
      // Normalize q before sending to backend (accent-strip, lowercase)
      params = params.set('q', normalizeSearchString(cleanedCriteria.q));
    }
    if (cleanedCriteria.page !== undefined) {
      params = params.set('page', cleanedCriteria.page.toString());
    }
    if (cleanedCriteria.size !== undefined) {
      params = params.set('size', cleanedCriteria.size.toString());
    }
    if (cleanedCriteria.sort) {
      params = params.set('sort', cleanedCriteria.sort);
    }

    // Create the observable with shareReplay to deduplicate simultaneous requests
    const request$ = this.http.get<any>(`${this.baseUrl}/search`, { params }).pipe(
      map((response) => ({
        content: response.content.map((car: any) => this.mapBackendCarToFrontend(car)),
        totalElements: response.totalElements,
        totalPages: response.totalPages,
        currentPage: response.currentPage,
        pageSize: response.pageSize,
        hasNext: response.hasNext,
        hasPrevious: response.hasPrevious,
      })),
      shareReplay({ bufferSize: 1, refCount: true })
    );

    // Add to cache
    this.searchCache.set(cacheKey, request$);

    // Limit cache size
    if (this.searchCache.size > this.CACHE_SIZE) {
      const firstKey = this.searchCache.keys().next().value;
      if (firstKey) {
        this.searchCache.delete(firstKey);
      }
    }

    return request$;
  }

  /**
   * Clean search criteria by removing empty strings, null values, and empty arrays
   * This minimizes the JSON payload and cache key size
   */
  private cleanCriteria(criteria: CarSearchCriteria): CarSearchCriteria {
    const cleaned: CarSearchCriteria = {};

    // Only include non-empty, non-null values
    if (criteria.minPrice !== undefined && criteria.minPrice !== null) {
      cleaned.minPrice = criteria.minPrice;
    }
    if (criteria.maxPrice !== undefined && criteria.maxPrice !== null) {
      cleaned.maxPrice = criteria.maxPrice;
    }
    if (criteria.make && criteria.make.trim()) {
      cleaned.make = criteria.make.trim();
    }
    if (criteria.model && criteria.model.trim()) {
      cleaned.model = criteria.model.trim();
    }
    if (criteria.minYear !== undefined && criteria.minYear !== null) {
      cleaned.minYear = criteria.minYear;
    }
    if (criteria.maxYear !== undefined && criteria.maxYear !== null) {
      cleaned.maxYear = criteria.maxYear;
    }
    if (criteria.minSeats !== undefined && criteria.minSeats !== null) {
      cleaned.minSeats = criteria.minSeats;
    }
    if (criteria.transmission) {
      cleaned.transmission = criteria.transmission;
    }
    if (criteria.features && criteria.features.length > 0) {
      cleaned.features = criteria.features;
    }
    if (criteria.page !== undefined && criteria.page !== null) {
      cleaned.page = criteria.page;
    }
    if (criteria.size !== undefined && criteria.size !== null) {
      cleaned.size = criteria.size;
    }
    if (criteria.sort && criteria.sort.trim()) {
      cleaned.sort = criteria.sort.trim();
    }
    if (criteria.q?.trim()) {
      cleaned.q = criteria.q.trim();
    }

    return cleaned;
  }

  /**
   * Get the maximum price per day from all available cars
   * Used for dynamic price slider range
   */
  getMaxPrice(): Observable<number> {
    const params = new HttpParams()
      .set('page', '0')
      .set('size', '1')
      .set('sort', 'pricePerDay,desc');

    return this.http.get<any>(`${this.baseUrl}/search`, { params }).pipe(
      map((response) => {
        if (response.content && response.content.length > 0) {
          return Math.ceil(response.content[0].pricePerDay);
        }
        return 500; // Fallback to 500 if no cars found
      })
    );
  }

  /**
   * Get cars by location with 20km radius filtering
   * @param location Search location (city name)
   * @param radiusKm Search radius in kilometers (default: 20)
   */
  getCarsByLocation(location: string, radiusKm: number = 20): Observable<Car[]> {
    // Get all cars and filter client-side for radius search
    return this.getCars().pipe(
      map((cars) => cars.filter((car) => isWithinRadius(car.location, location, radiusKm)))
    );
  }

  /**
   * Alternative: Use backend endpoint if it exists and supports radius search
   * This is a fallback method in case backend implements radius filtering
   */
  getCarsByLocationBackend(location: string): Observable<Car[]> {
    return this.http
      .get<any[]>(`${this.baseUrl}/location/${location}`)
      .pipe(map((cars) => cars.map((car) => this.mapBackendCarToFrontend(car))));
  }

  getCarById(id: string): Observable<Car> {
    return this.http
      .get<any>(`${this.baseUrl}/${id}`)
      .pipe(map((car) => this.mapBackendCarToFrontend(car)));
  }

  getCarReviews(id: string): Observable<Review[]> {
    return this.http.get<Review[]>(`${this.baseUrl}/${id}/reviews`);
  }

  /**
   * Clear the search cache
   * Useful after CRUD operations on cars to ensure fresh data
   * Also clears HTTP interceptor cache
   */
  clearSearchCache(): void {
    this.searchCache.clear();
    // Clear HTTP cache for car-related endpoints
    clearHttpCacheByPattern('/api/cars');
  }

  // ========== Owner Operations ==========

  /**
   * Get all cars for a specific owner
   * @param ownerEmail Email of the owner
   */
  getOwnerCars(ownerEmail: string): Observable<Car[]> {
    return this.http
      .get<any[]>(`${this.baseUrl}/owner/${ownerEmail}`, {
        withCredentials: true,
      })
      .pipe(map((cars) => cars.map((car) => this.mapBackendCarToFrontend(car))));
  }

  /**
   * Add a new car (owner only)
   * @param carData Car data to add (frontend format with 'make')
   */
  addCar(carData: Partial<Car>): Observable<Car> {
    // Map frontend 'make' to backend 'brand'
    const { make, ...rest } = carData;
    const backendData = {
      ...rest,
      brand: make,
    };

    return this.http
      .post<any>(`${this.baseUrl}/add`, backendData, {
        withCredentials: true,
      })
      .pipe(map((car) => this.mapBackendCarToFrontend(car)));
  }

  /**
   * Add a new car using multipart/form-data (local image upload).
   * Sends JSON metadata as a part named "car" and image files as parts named "images".
   */
  addCarMultipart(carData: Partial<Car>, images: File[]): Observable<Car> {
    const { make, imageUrl, imageUrls, ...rest } = carData;

    const backendData = {
      ...rest,
      brand: make,
    };

    const formData = new FormData();
    formData.append('car', new Blob([JSON.stringify(backendData)], { type: 'application/json' }));

    for (const file of images ?? []) {
      formData.append('images', file);
    }

    return this.http
      .post<any>(`${this.baseUrl}/add`, formData, {
        withCredentials: true,
      })
      .pipe(map((car) => this.mapBackendCarToFrontend(car)));
  }

  /**
   * Update an existing car (owner only)
   * @param id Car ID
   * @param carData Updated car data (frontend format with 'make')
   */
  updateCar(id: string, carData: Partial<Car>): Observable<Car> {
    // Map frontend 'make' to backend 'brand'
    const { make, ...rest } = carData;
    const backendData = {
      ...rest,
      brand: make,
    };

    return this.http
      .put<any>(`${this.baseUrl}/${id}`, backendData, {
        withCredentials: true,
      })
      .pipe(map((car) => this.mapBackendCarToFrontend(car)));
  }

  /**
   * Delete a car (owner only)
   * @param id Car ID
   */
  deleteCar(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`, {
      withCredentials: true,
    });
  }

  /**
   * Toggle car availability (owner only)
   * @param id Car ID
   * @param available New availability status
   */
  toggleAvailability(id: string, available: boolean): Observable<Car> {
    return this.http
      .patch<any>(
        `${this.baseUrl}/${id}/availability`,
        { available },
        {
          withCredentials: true,
        }
      )
      .pipe(map((car) => this.mapBackendCarToFrontend(car)));
  }
}