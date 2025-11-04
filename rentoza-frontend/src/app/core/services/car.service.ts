import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';

import { environment } from '@environments/environment';
import { Car } from '@core/models/car.model';
import { Review } from '@core/models/review.model';
import { CarSearchCriteria, PagedResponse } from '@core/models/car-search.model';
import { isWithinRadius } from '@core/utils/distance.util';
import { normalizeSearchString } from '@core/utils/string-normalization.util';

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

  constructor(private readonly http: HttpClient) {}

  /**
   * Map backend DTO to frontend Car model
   * Backend uses 'brand', frontend uses 'make'
   */
  private mapBackendCarToFrontend(backendCar: any): Car {
    const { brand, ...rest } = backendCar;
    return {
      ...rest,
      make: brand, // Map 'brand' to 'make'
    } as Car;
  }

  getCars(): Observable<Car[]> {
    return this.http
      .get<any[]>(this.baseUrl)
      .pipe(map((cars) => cars.map((car) => this.mapBackendCarToFrontend(car))));
  }

  /**
   * Search cars with filters, sorting, and pagination
   * @param criteria Search criteria
   * @returns Paginated response with cars
   */
  searchCars(criteria: CarSearchCriteria): Observable<PagedResponse<Car>> {
    let params = new HttpParams();

    // Add all non-null/undefined criteria to params
    // Apply normalization to text-based filters
    if (criteria.minPrice !== undefined) {
      params = params.set('minPrice', criteria.minPrice.toString());
    }
    if (criteria.maxPrice !== undefined) {
      params = params.set('maxPrice', criteria.maxPrice.toString());
    }
    if (criteria.vehicleType) {
      params = params.set('vehicleType', normalizeSearchString(criteria.vehicleType));
    }
    if (criteria.make) {
      params = params.set('make', normalizeSearchString(criteria.make));
    }
    if (criteria.model) {
      params = params.set('model', normalizeSearchString(criteria.model));
    }
    if (criteria.minYear !== undefined) {
      params = params.set('minYear', criteria.minYear.toString());
    }
    if (criteria.maxYear !== undefined) {
      params = params.set('maxYear', criteria.maxYear.toString());
    }
    if (criteria.location) {
      params = params.set('location', normalizeSearchString(criteria.location));
    }
    if (criteria.minSeats !== undefined) {
      params = params.set('minSeats', criteria.minSeats.toString());
    }
    if (criteria.transmission) {
      params = params.set('transmission', criteria.transmission);
    }
    if (criteria.features && criteria.features.length > 0) {
      // Send features as comma-separated string
      params = params.set('features', criteria.features.join(','));
    }
    if (criteria.page !== undefined) {
      params = params.set('page', criteria.page.toString());
    }
    if (criteria.size !== undefined) {
      params = params.set('size', criteria.size.toString());
    }
    if (criteria.sort) {
      params = params.set('sort', criteria.sort);
    }

    return this.http.get<any>(`${this.baseUrl}/search`, { params }).pipe(
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

  // ========== Owner Operations ==========

  /**
   * Get all cars for a specific owner
   * @param ownerEmail Email of the owner
   */
  getOwnerCars(ownerEmail: string): Observable<Car[]> {
    return this.http
      .get<any[]>(`${this.baseUrl}/owner/${ownerEmail}`)
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
      .post<any>(`${this.baseUrl}/add`, backendData)
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
      .put<any>(`${this.baseUrl}/${id}`, backendData)
      .pipe(map((car) => this.mapBackendCarToFrontend(car)));
  }

  /**
   * Delete a car (owner only)
   * @param id Car ID
   */
  deleteCar(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  /**
   * Toggle car availability (owner only)
   * @param id Car ID
   * @param available New availability status
   */
  toggleAvailability(id: string, available: boolean): Observable<Car> {
    return this.http
      .patch<any>(`${this.baseUrl}/${id}/availability`, { available })
      .pipe(map((car) => this.mapBackendCarToFrontend(car)));
  }
}
