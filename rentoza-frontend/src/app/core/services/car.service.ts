import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';

import { environment } from '@environments/environment';
import { Car } from '@core/models/car.model';
import { Review } from '@core/models/review.model';
import { isWithinRadius } from '@core/utils/distance.util';

/**
 * Backend Car DTO interface (uses 'brand' instead of 'make')
 */
interface BackendCarDTO {
  id: string;
  brand: string;  // Backend uses 'brand'
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
      make: brand  // Map 'brand' to 'make'
    } as Car;
  }

  getCars(): Observable<Car[]> {
    return this.http.get<any[]>(this.baseUrl).pipe(
      map(cars => cars.map(car => this.mapBackendCarToFrontend(car)))
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
      map(cars => cars.filter(car => isWithinRadius(car.location, location, radiusKm)))
    );
  }

  /**
   * Alternative: Use backend endpoint if it exists and supports radius search
   * This is a fallback method in case backend implements radius filtering
   */
  getCarsByLocationBackend(location: string): Observable<Car[]> {
    return this.http.get<any[]>(`${this.baseUrl}/location/${location}`).pipe(
      map(cars => cars.map(car => this.mapBackendCarToFrontend(car)))
    );
  }

  getCarById(id: string): Observable<Car> {
    return this.http.get<any>(`${this.baseUrl}/${id}`).pipe(
      map(car => this.mapBackendCarToFrontend(car))
    );
  }

  getCarReviews(id: string): Observable<Review[]> {
    return this.http.get<Review[]>(`${this.baseUrl}/${id}/reviews`);
  }
}
