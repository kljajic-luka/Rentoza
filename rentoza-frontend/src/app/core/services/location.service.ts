import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of, shareReplay, catchError, map } from 'rxjs';
import { environment } from '../../../environments/environment';

/**
 * Represents a geographic coordinate pair
 */
export interface GeoCoordinates {
  latitude: number;
  longitude: number;
}

/**
 * Car location for map display
 */
export interface CarLocation {
  id: number;
  latitude: number;
  longitude: number;
  title: string;
  pricePerDay: number;
  available: boolean;
  distanceKm?: number;
}

/**
 * Delivery fee calculation result
 */
export interface DeliveryFeeResult {
  available: boolean;
  fee?: number;
  calculatedFee?: number;
  distanceKm?: number;
  estimatedMinutes?: number;
  routingSource?: 'OSRM' | 'HAVERSINE_FALLBACK';
  appliedPoiCode?: string;
  unavailableReason?: string;
  maxRadiusKm?: number;
}

/**
 * Point of Interest for delivery
 */
export interface DeliveryPoi {
  id: number;
  name: string;
  code: string;
  latitude: number;
  longitude: number;
  radiusKm: number;
  poiType: string;
  fixedFee?: number;
  minimumFee?: number;
  surcharge?: number;
}

/**
 * Service for location-related operations
 *
 * Provides:
 * - Nearby car search
 * - Delivery fee calculation
 * - POI lookup
 * - Geocoding (via backend proxy to respect rate limits)
 *
 * @since 2.4.0 (Geospatial Location Migration)
 */
@Injectable({
  providedIn: 'root',
})
export class LocationService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.baseApiUrl;

  // Cache for POIs (rarely changes)
  private poisCache$: Observable<DeliveryPoi[]> | null = null;

  /**
   * Find cars near a location
   *
   * @param latitude Center latitude
   * @param longitude Center longitude
   * @param radiusKm Search radius in kilometers
   * @returns Observable of cars with distance
   */
  findNearbyCars(latitude: number, longitude: number, radiusKm = 25): Observable<CarLocation[]> {
    const params = new HttpParams()
      .set('latitude', latitude.toString())
      .set('longitude', longitude.toString())
      .set('radiusKm', radiusKm.toString());

    return this.http.get<CarLocation[]>(`${this.baseUrl}/cars/nearby`, { params }).pipe(
      catchError((error) => {
        console.error('Failed to fetch nearby cars:', error);
        return of([]);
      })
    );
  }

  /**
   * Calculate delivery fee for a car to a destination
   *
   * @param carId Car ID
   * @param destination Delivery destination coordinates
   * @returns Observable of delivery fee result
   */
  calculateDeliveryFee(carId: number, destination: GeoCoordinates): Observable<DeliveryFeeResult> {
    const params = new HttpParams()
      .set('carId', carId.toString())
      .set('latitude', destination.latitude.toString())
      .set('longitude', destination.longitude.toString());

    return this.http
      .get<DeliveryFeeResult>(`${this.baseUrl}/delivery/calculate-fee`, { params })
      .pipe(
        catchError((error) => {
          console.error('Failed to calculate delivery fee:', error);
          return of({
            available: false,
            unavailableReason: 'Unable to calculate delivery fee',
          });
        })
      );
  }

  /**
   * Check if a car can deliver to a location
   *
   * @param carId Car ID
   * @param destination Delivery destination
   * @returns Observable of boolean
   */
  canDeliver(carId: number, destination: GeoCoordinates): Observable<boolean> {
    return this.calculateDeliveryFee(carId, destination).pipe(map((result) => result.available));
  }

  /**
   * Get POIs near a location
   *
   * @param latitude Center latitude
   * @param longitude Center longitude
   * @param maxDistanceKm Max distance to search
   * @returns Observable of nearby POIs
   */
  getNearbyPois(
    latitude: number,
    longitude: number,
    maxDistanceKm = 10
  ): Observable<DeliveryPoi[]> {
    const params = new HttpParams()
      .set('latitude', latitude.toString())
      .set('longitude', longitude.toString())
      .set('maxDistanceKm', maxDistanceKm.toString());

    return this.http.get<DeliveryPoi[]>(`${this.baseUrl}/delivery/pois/nearby`, { params }).pipe(
      catchError((error) => {
        console.error('Failed to fetch nearby POIs:', error);
        return of([]);
      })
    );
  }

  /**
   * Get all active POIs (cached)
   *
   * @returns Observable of all POIs
   */
  getAllPois(): Observable<DeliveryPoi[]> {
    if (!this.poisCache$) {
      this.poisCache$ = this.http.get<DeliveryPoi[]>(`${this.baseUrl}/delivery/pois`).pipe(
        shareReplay(1),
        catchError((error) => {
          console.error('Failed to fetch POIs:', error);
          this.poisCache$ = null;
          return of([]);
        })
      );
    }
    return this.poisCache$;
  }

  /**
   * Calculate straight-line distance between two points (client-side)
   * Uses Haversine formula
   *
   * @param from Start coordinates
   * @param to End coordinates
   * @returns Distance in kilometers
   */
  calculateDistance(from: GeoCoordinates, to: GeoCoordinates): number {
    const R = 6371; // Earth radius in km
    const dLat = this.toRadians(to.latitude - from.latitude);
    const dLon = this.toRadians(to.longitude - from.longitude);
    const lat1 = this.toRadians(from.latitude);
    const lat2 = this.toRadians(to.latitude);

    const a =
      Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

    return R * c;
  }

  /**
   * Get user's current location
   *
   * @returns Promise of coordinates
   */
  getCurrentLocation(): Promise<GeoCoordinates> {
    return new Promise((resolve, reject) => {
      if (!navigator.geolocation) {
        reject(new Error('Geolocation not supported'));
        return;
      }

      navigator.geolocation.getCurrentPosition(
        (position) => {
          resolve({
            latitude: position.coords.latitude,
            longitude: position.coords.longitude,
          });
        },
        (error) => {
          reject(error);
        },
        {
          enableHighAccuracy: true,
          timeout: 10000,
          maximumAge: 0,
        }
      );
    });
  }

  /**
   * Format distance for display
   *
   * @param distanceKm Distance in kilometers
   * @returns Formatted string (e.g., "5.2 km" or "800 m")
   */
  formatDistance(distanceKm: number): string {
    if (distanceKm < 1) {
      return `${Math.round(distanceKm * 1000)} m`;
    }
    return `${distanceKm.toFixed(1)} km`;
  }

  /**
   * Format duration for display
   *
   * @param minutes Duration in minutes
   * @returns Formatted string (e.g., "15 min" or "1 hr 30 min")
   */
  formatDuration(minutes: number): string {
    if (minutes < 60) {
      return `${Math.round(minutes)} min`;
    }
    const hours = Math.floor(minutes / 60);
    const remainingMinutes = Math.round(minutes % 60);
    if (remainingMinutes === 0) {
      return `${hours} hr`;
    }
    return `${hours} hr ${remainingMinutes} min`;
  }

  private toRadians(degrees: number): number {
    return degrees * (Math.PI / 180);
  }
}
