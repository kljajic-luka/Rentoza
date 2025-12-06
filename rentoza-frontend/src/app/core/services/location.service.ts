import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import {
  Observable,
  of,
  shareReplay,
  catchError,
  map,
  Subject,
  debounceTime,
  distinctUntilChanged,
  switchMap,
  tap,
  BehaviorSubject,
} from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  GeoCoordinates,
  GeoPointDTO,
  GeocodeSuggestion,
  ReverseGeocodeResult,
  GeospatialSearchFilters,
  CarSearchResult,
  CarSearchResponse,
  DeliveryFeeResult,
  DeliveryAvailability,
  DeliveryPoi,
  CarMarker,
  LocationValidation,
  SERBIA_BOUNDS,
  DEFAULT_MAP_CENTER,
  validateLocation,
  isWithinSerbia,
  ObfuscatedGeoPointDTO,
} from '../models/location.model';

// Re-export types for consumers that import from the service
export type {
  GeoCoordinates,
  GeoPointDTO,
  GeocodeSuggestion,
  ReverseGeocodeResult,
  GeospatialSearchFilters,
  CarSearchResult,
  CarSearchResponse,
  DeliveryFeeResult,
  DeliveryAvailability,
  DeliveryPoi,
  CarMarker,
  LocationValidation,
  ObfuscatedGeoPointDTO,
} from '../models/location.model';

export { SERBIA_BOUNDS, DEFAULT_MAP_CENTER } from '../models/location.model';

/**
 * Car location for map display (legacy interface, use CarMarker for new code)
 * @deprecated Use CarMarker from location.model.ts instead
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

  // Geocoding autocomplete state
  private readonly geocodeSearchSubject = new Subject<string>();
  readonly geocodeSuggestions$ = this.geocodeSearchSubject.pipe(
    debounceTime(300),
    distinctUntilChanged(),
    switchMap((query) => (query.length >= 2 ? this.geocodeAddress(query) : of([])))
  );

  /**
   * Geocode an address string to coordinates
   * Uses backend proxy to Mapbox/Nominatim API
   *
   * @param address Address or place name to geocode
   * @returns Observable of geocode suggestions
   */
  geocodeAddress(address: string): Observable<GeocodeSuggestion[]> {
    if (!address || address.trim().length < 2) {
      return of([]);
    }

    const params = new HttpParams().set('query', address.trim()).set('limit', '5');

    return this.http.get<any[]>(`${this.baseUrl}/locations/geocode`, { params }).pipe(
      map((responses) => {
        // Backend returns: { displayName, latitude, longitude, city, zipCode, type }
        // Frontend expects: { id, latitude, longitude, formattedAddress, address, city, zipCode, country, placeType }
        return responses.map(
          (response, index) =>
            ({
              id: `geocode-${index}-${response.latitude}-${response.longitude}`,
              latitude: response.latitude,
              longitude: response.longitude,
              formattedAddress: response.displayName || 'Nepoznata lokacija',
              address: response.displayName || 'Nepoznata adresa',
              city: response.city || undefined,
              zipCode: response.zipCode,
              country: 'Srbija',
              placeType: (response.type || 'address') as GeocodeSuggestion['placeType'],
            } as GeocodeSuggestion)
        );
      }),
      catchError((error) => {
        console.error('Geocoding failed:', error);
        return of([]);
      })
    );
  }

  /**
   * Trigger geocode search (for autocomplete)
   *
   * @param query Search query
   */
  searchAddress(query: string): void {
    this.geocodeSearchSubject.next(query);
  }

  /**
   * Reverse geocode coordinates to address
   *
   * @param latitude Latitude
   * @param longitude Longitude
   * @returns Observable of reverse geocode result
   */
  reverseGeocode(latitude: number, longitude: number): Observable<ReverseGeocodeResult> {
    const params = new HttpParams()
      .set('latitude', latitude.toString())
      .set('longitude', longitude.toString());

    return this.http.get<any>(`${this.baseUrl}/locations/reverse-geocode`, { params }).pipe(
      map((response) => {
        // Backend returns: { address, city, zipCode, latitude, longitude }
        // Frontend expects: { formattedAddress, address, city, zipCode, country, placeType }
        return {
          formattedAddress: response.address || `${latitude.toFixed(4)}, ${longitude.toFixed(4)}`,
          address: response.address || 'Nepoznata adresa',
          city: response.city || undefined,
          zipCode: response.zipCode,
          country: 'Srbija',
          placeType: 'address',
        } as ReverseGeocodeResult;
      }),
      catchError((error) => {
        console.error('Reverse geocoding failed:', error);
        return of({
          formattedAddress: 'Unknown location',
          address: 'Unknown',
          city: 'Unknown',
          zipCode: undefined,
          country: 'Serbia',
          placeType: 'address',
        });
      })
    );
  }

  /**
   * Search cars near a location with filters
   *
   * @param filters Search filters including location and radius
   * @returns Observable of car search response
   */
  searchCars(filters: GeospatialSearchFilters): Observable<CarSearchResponse> {
    let params = new HttpParams();

    // Location filter (backend uses string-based location search)
    if (filters.location) {
      params = params.set('location', filters.location);
    }

    // Price filters
    if (filters.pricePerDayMin != null) {
      params = params.set('minPrice', filters.pricePerDayMin.toString());
    }
    if (filters.pricePerDayMax != null) {
      params = params.set('maxPrice', filters.pricePerDayMax.toString());
    }

    // Vehicle filters
    if (filters.fuelType) {
      params = params.set('fuelType', filters.fuelType);
    }
    if (filters.transmission) {
      params = params.set('transmission', filters.transmission);
    }
    if (filters.carType && filters.carType.length > 0) {
      params = params.set('vehicleType', filters.carType.join(','));
    }

    // Pagination
    params = params.set('page', (filters.page ?? 0).toString());
    params = params.set('size', (filters.pageSize ?? 20).toString());

    // Store search center for distance calculation
    const searchLat = filters.latitude;
    const searchLng = filters.longitude;

    return this.http.get<any>(`${this.baseUrl}/cars/search`, { params }).pipe(
      map((response) => {
        // Backend returns: { content, totalElements, totalPages, currentPage, pageSize, hasNext, hasPrevious }
        // Frontend expects: { data, pagination }
        const cars = response.content || [];

        // Transform each car to CarSearchResult format
        const data: CarSearchResult[] = cars.map((car: any) => {
          // Calculate distance if we have search coordinates and car coordinates
          let distanceKm: number | undefined;
          if (
            searchLat != null &&
            searchLng != null &&
            car.locationLatitude != null &&
            car.locationLongitude != null
          ) {
            distanceKm = this.calculateDistance(
              { latitude: searchLat, longitude: searchLng },
              {
                latitude: parseFloat(car.locationLatitude),
                longitude: parseFloat(car.locationLongitude),
              }
            );
            distanceKm = Math.round(distanceKm * 10) / 10; // Round to 1 decimal
          }

          return {
            id: car.id,
            brand: car.brand,
            model: car.model,
            year: car.year,
            pricePerDay: parseFloat(car.pricePerDay) || 0,
            locationGeoPoint: {
              latitude: car.locationLatitude ? parseFloat(car.locationLatitude) : 0,
              longitude: car.locationLongitude ? parseFloat(car.locationLongitude) : 0,
              city: car.locationCity || car.location || 'Nepoznato',
              obfuscationRadiusMeters: car.isExactLocation ? 0 : 500,
              obfuscationApplied: !car.isExactLocation,
            },
            distanceKm,
            imageUrl: car.imageUrl,
            imageUrls: car.imageUrls,
            rating: car.ownerRating,
            reviewCount: car.ownerTripCount,
            features: car.features,
            transmission: car.transmissionType,
            fuelType: car.fuelType,
            seats: car.seats,
            available: car.available,
          } as CarSearchResult;
        });

        return {
          data,
          pagination: {
            total: response.totalElements || 0,
            page: response.currentPage ?? filters.page ?? 0,
            pageSize: response.pageSize ?? filters.pageSize ?? 20,
            totalPages: response.totalPages || 0,
          },
        } as CarSearchResponse;
      }),
      catchError((error) => {
        console.error('Car search failed:', error);
        return of({
          data: [],
          pagination: {
            total: 0,
            page: filters.page ?? 0,
            pageSize: filters.pageSize ?? 20,
            totalPages: 0,
          },
        });
      })
    );
  }

  /**
   * Get obfuscated location for a car (privacy protection)
   *
   * @param carId Car ID
   * @param hasBookingHistory Whether user has booking history with this car
   * @returns Observable of obfuscated location
   */
  getObfuscatedLocation(
    carId: number,
    hasBookingHistory = false
  ): Observable<ObfuscatedGeoPointDTO> {
    const params = new HttpParams().set('hasBookingHistory', hasBookingHistory.toString());

    return this.http
      .get<ObfuscatedGeoPointDTO>(`${this.baseUrl}/cars/${carId}/location`, { params })
      .pipe(
        catchError((error) => {
          console.error('Failed to get car location:', error);
          return of({
            latitude: DEFAULT_MAP_CENTER.latitude,
            longitude: DEFAULT_MAP_CENTER.longitude,
            city: 'Unknown',
            obfuscationRadiusMeters: 500,
            obfuscationApplied: true,
          });
        })
      );
  }

  /**
   * Validate if coordinates are within Serbia bounds
   *
   * @param latitude Latitude
   * @param longitude Longitude
   * @returns Validation result
   */
  validateCoordinates(latitude: number, longitude: number): LocationValidation {
    return validateLocation(latitude, longitude);
  }

  /**
   * Check if coordinates are within Serbia
   *
   * @param latitude Latitude
   * @param longitude Longitude
   * @returns True if within Serbia bounds
   */
  isWithinSerbiaBounds(latitude: number, longitude: number): boolean {
    return isWithinSerbia(latitude, longitude);
  }

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
