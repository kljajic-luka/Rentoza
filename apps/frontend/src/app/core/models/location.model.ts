/**
 * Location Models
 *
 * Type-safe DTOs for geospatial operations synchronized with backend GeoPoint.
 * These models support the Geospatial Location Migration (Phase 2.4+).
 *
 * BACKEND SYNC:
 * - GeoPointDTO ↔ org.example.rentoza.common.GeoPoint (Java BigDecimal → TS number)
 * - SearchFilters ↔ CarSearchCriteria query parameters
 * - CarSearchResult ↔ CarResponseDTO with geospatial enrichment
 *
 * @since 2.4.0 (Geospatial Location Migration)
 */

// ============================================================================
// CORE GEOSPATIAL TYPES
// ============================================================================

/**
 * Core geographic coordinate pair.
 * Matches backend GeoPoint embeddable without address details.
 */
export interface GeoCoordinates {
  latitude: number;
  longitude: number;
}

/**
 * Full GeoPoint DTO matching backend GeoPoint embeddable.
 * Used for car locations, booking pickups, and user-entered addresses.
 *
 * BACKEND: org.example.rentoza.common.GeoPoint
 */
export interface GeoPointDTO {
  latitude: number;
  longitude: number;
  address?: string;
  city?: string;
  zipCode?: string;
  /** GPS accuracy in meters (null = unknown) */
  accuracyMeters?: number;
}

/**
 * Obfuscated location returned for privacy (unbooked guests).
 * Server applies ±500m offset before returning.
 */
export interface ObfuscatedGeoPointDTO {
  latitude: number;
  longitude: number;
  city: string;
  /** Radius within which the actual location falls */
  obfuscationRadiusMeters: number;
  /** Whether obfuscation was applied (false = exact location) */
  obfuscationApplied: boolean;
}

// ============================================================================
// GEOCODING TYPES
// ============================================================================

/**
 * Geocoding suggestion from Mapbox (via backend proxy).
 * Used for address autocomplete in Add Car wizard.
 */
export interface GeocodeSuggestion {
  /** Unique identifier from geocoding provider */
  id: string;
  latitude: number;
  longitude: number;
  /** Full formatted address (e.g., "Terazije 26, Belgrade, Serbia") */
  formattedAddress: string;
  /** Street address only (e.g., "Terazije 26") */
  address: string;
  city: string;
  zipCode?: string;
  /** Country name */
  country: string;
  /** Confidence in meters (lower = more accurate) */
  accuracyMeters?: number;
  /** Type of place: address, place, region, postcode */
  placeType: 'address' | 'place' | 'region' | 'postcode' | 'poi';
}

/**
 * Reverse geocoding result (coordinates → address).
 */
export interface ReverseGeocodeResult {
  formattedAddress: string;
  address: string;
  city: string;
  zipCode?: string;
  country: string;
  placeType: string;
}

// ============================================================================
// CAR SEARCH TYPES
// ============================================================================

/**
 * Geospatial search filters for car discovery.
 * Extends CarSearchCriteria with location-based parameters.
 */
export interface GeospatialSearchFilters {
  // === LOCATION FILTERS ===
  /** Location string (city name) for backend search */
  location?: string;
  /** Center latitude for proximity search */
  latitude?: number;
  /** Center longitude for proximity search */
  longitude?: number;
  /** Search radius in kilometers (default: 25, max: 100) */
  radiusKm?: number;
  /** Maximum distance from search center */
  distanceMaxKm?: number;

  // === PRICE FILTERS ===
  pricePerDayMin?: number;
  pricePerDayMax?: number;

  // === DELIVERY FILTERS ===
  /** Only show cars that offer delivery to search location */
  deliveryAvailable?: boolean;

  // === VEHICLE FILTERS ===
  /** Car body types (Sedan, SUV, Hatchback, Van, Coupe) */
  carType?: string[];
  /** Transmission type */
  transmission?: 'MANUAL' | 'AUTOMATIC';
  /** Fuel type */
  fuelType?: 'PETROL' | 'DIESEL' | 'ELECTRIC' | 'HYBRID';

  // === RATING FILTERS ===
  minRating?: number;

  // === PAGINATION ===
  page?: number;
  pageSize?: number;
}

/**
 * Car search result with geospatial enrichment.
 * Includes distance from search center and delivery info.
 */
export interface CarSearchResult {
  id: number;
  brand: string;
  model: string;
  year: number;
  pricePerDay: number;

  // === LOCATION ===
  /** Location coordinates (may be obfuscated for privacy) */
  locationGeoPoint: ObfuscatedGeoPointDTO;
  /** Distance from search center in km */
  distanceKm?: number;

  // === MEDIA ===
  imageUrl?: string;
  imageUrls?: string[];

  // === RATINGS ===
  rating?: number;
  reviewCount?: number;

  // === FEATURES ===
  features?: string[];
  transmission?: string;
  fuelType?: string;
  seats?: number;

  // === DELIVERY INFO (enriched after search) ===
  /** Whether car can deliver to search location */
  deliveryAvailable?: boolean;
  /** Calculated delivery fee (if available) */
  deliveryFee?: number;
  /** Delivery distance in km */
  deliveryDistance?: number;

  // === AVAILABILITY ===
  available: boolean;
}

/**
 * Paginated car search response.
 */
export interface CarSearchResponse {
  data: CarSearchResult[];
  pagination: {
    total: number;
    page: number;
    pageSize: number;
    totalPages: number;
  };
}

// ============================================================================
// CAR MARKER TYPES (FOR MAP DISPLAY)
// ============================================================================

/**
 * Lightweight car marker for map display.
 * Used by LocationPickerComponent for multi-marker mode.
 */
export interface CarMarker {
  carId: number;
  latitude: number;
  longitude: number;
  /** Display title (e.g., "BMW X5") */
  title?: string;
  /** Price for tooltip */
  pricePerDay?: number;
  /** Distance from center for sorting */
  distanceKm?: number;
  /** Custom marker color */
  markerColor?: string;
  /** Whether marker is selected */
  selected?: boolean;
}

// ============================================================================
// DELIVERY FEE TYPES
// ============================================================================

/**
 * Delivery fee calculation result from backend.
 * BACKEND: DeliveryFeeCalculator.DeliveryFeeResult
 */
export interface DeliveryFeeResult {
  available: boolean;
  /** Final delivery fee after POI rules */
  fee?: number;
  /** Calculated fee before POI adjustments */
  calculatedFee?: number;
  /** Driving distance in km (from OSRM) */
  distanceKm?: number;
  /** Estimated driving time in minutes */
  estimatedMinutes?: number;
  /** Routing calculation source */
  routingSource?: 'OSRM' | 'HAVERSINE_FALLBACK';
  /** POI code if fee override applied (e.g., "BEG" for airport) */
  appliedPoiCode?: string;
  /** Reason if delivery unavailable */
  unavailableReason?: string;
  /** Car's max delivery radius (for "outside range" errors) */
  maxRadiusKm?: number;
  /** Fee breakdown for display */
  breakdown?: DeliveryFeeBreakdown;
}

/**
 * Delivery fee breakdown for detailed display.
 */
export interface DeliveryFeeBreakdown {
  baseFee: number;
  distanceFee: number;
  poiSurcharge?: number;
  poiName?: string;
}

/**
 * Delivery availability check result.
 */
export interface DeliveryAvailability {
  available: boolean;
  reason?: string;
  maxRadiusKm?: number;
  actualDistanceKm?: number;
}

// ============================================================================
// POI (POINT OF INTEREST) TYPES
// ============================================================================

/**
 * Delivery Point of Interest.
 * BACKEND: org.example.rentoza.delivery.DeliveryPoi
 */
export interface DeliveryPoi {
  id: number;
  name: string;
  code: string;
  latitude: number;
  longitude: number;
  radiusKm: number;
  poiType: PoiType;
  fixedFee?: number;
  minimumFee?: number;
  surcharge?: number;
  active: boolean;
}

/**
 * POI type enumeration.
 */
export type PoiType =
  | 'AIRPORT'
  | 'TRAIN_STATION'
  | 'BUS_STATION'
  | 'HOTEL_ZONE'
  | 'CITY_CENTER'
  | 'SHOPPING_MALL'
  | 'BUSINESS_DISTRICT'
  | 'TOURIST_ATTRACTION'
  | 'PORT'
  | 'OTHER';

// ============================================================================
// LOCATION VALIDATION
// ============================================================================

/**
 * Serbia geographic bounds for coordinate validation.
 */
export const SERBIA_BOUNDS = {
  minLatitude: 42.2,
  maxLatitude: 46.2,
  minLongitude: 18.8,
  maxLongitude: 23.0,
} as const;

/**
 * Default map center (Belgrade, Serbia).
 */
export const DEFAULT_MAP_CENTER: GeoCoordinates = {
  latitude: 44.8176,
  longitude: 20.4569,
};

/**
 * Location accuracy levels for UI display.
 */
export type LocationAccuracy = 'PRECISE' | 'APPROXIMATE' | 'UNKNOWN';

/**
 * Location validation result.
 */
export interface LocationValidation {
  isValid: boolean;
  isWithinSerbia: boolean;
  accuracy: LocationAccuracy;
  errorMessage?: string;
}

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================

/**
 * Check if coordinates are within Serbia bounds.
 */
export function isWithinSerbia(lat: number, lon: number): boolean {
  return (
    lat >= SERBIA_BOUNDS.minLatitude &&
    lat <= SERBIA_BOUNDS.maxLatitude &&
    lon >= SERBIA_BOUNDS.minLongitude &&
    lon <= SERBIA_BOUNDS.maxLongitude
  );
}

/**
 * Get accuracy level from meters.
 */
export function getAccuracyLevel(metersAccuracy?: number): LocationAccuracy {
  if (metersAccuracy === undefined || metersAccuracy === null) {
    return 'UNKNOWN';
  }
  if (metersAccuracy < 50) {
    return 'PRECISE';
  }
  if (metersAccuracy < 500) {
    return 'APPROXIMATE';
  }
  return 'UNKNOWN';
}

/**
 * Validate location and return detailed result.
 */
export function validateLocation(
  lat: number | null | undefined,
  lon: number | null | undefined,
  accuracyMeters?: number
): LocationValidation {
  if (lat === null || lat === undefined || lon === null || lon === undefined) {
    return {
      isValid: false,
      isWithinSerbia: false,
      accuracy: 'UNKNOWN',
      errorMessage: 'Location coordinates are required',
    };
  }

  if (lat < -90 || lat > 90) {
    return {
      isValid: false,
      isWithinSerbia: false,
      accuracy: 'UNKNOWN',
      errorMessage: 'Latitude must be between -90 and 90',
    };
  }

  if (lon < -180 || lon > 180) {
    return {
      isValid: false,
      isWithinSerbia: false,
      accuracy: 'UNKNOWN',
      errorMessage: 'Longitude must be between -180 and 180',
    };
  }

  const withinSerbia = isWithinSerbia(lat, lon);

  return {
    isValid: true,
    isWithinSerbia: withinSerbia,
    accuracy: getAccuracyLevel(accuracyMeters),
    errorMessage: withinSerbia ? undefined : 'Location is outside Serbia',
  };
}