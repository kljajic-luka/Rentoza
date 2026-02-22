/**
 * Utility functions for calculating distances between locations
 * Uses Haversine formula for accurate geographic distance calculations
 */

export interface Coordinates {
  latitude: number;
  longitude: number;
}

/**
 * Serbian cities and their coordinates for location matching
 */
export const SERBIAN_CITIES: Record<string, Coordinates> = {
  // Major cities
  'beograd': { latitude: 44.7866, longitude: 20.4489 },
  'novi sad': { latitude: 45.2671, longitude: 19.8335 },
  'niš': { latitude: 43.3209, longitude: 21.8954 },
  'kragujevac': { latitude: 44.0125, longitude: 20.9114 },
  'subotica': { latitude: 46.1005, longitude: 19.6672 },
  'zrenjanin': { latitude: 45.3833, longitude: 20.3833 },
  'pančevo': { latitude: 44.8708, longitude: 20.6408 },
  'čačak': { latitude: 43.8914, longitude: 20.3497 },
  'kruševac': { latitude: 43.5800, longitude: 21.3278 },
  'kraljevo': { latitude: 43.7250, longitude: 20.6872 },
  'smederevo': { latitude: 44.6614, longitude: 20.9300 },
  'leskovac': { latitude: 42.9981, longitude: 21.9461 },
  'užice': { latitude: 43.8583, longitude: 19.8483 },
  'vranje': { latitude: 42.5514, longitude: 21.9022 },
  'šabac': { latitude: 44.7472, longitude: 19.6997 },
  'valjevo': { latitude: 44.2667, longitude: 19.8894 },
  'sombor': { latitude: 45.7744, longitude: 19.1122 },
  'požarevac': { latitude: 44.6222, longitude: 21.1897 },
  'pirot': { latitude: 43.1531, longitude: 22.5883 },
  'zaječar': { latitude: 43.9042, longitude: 22.2883 },
  'kikinda': { latitude: 45.8275, longitude: 20.4586 },
  'sremska mitrovica': { latitude: 44.9758, longitude: 19.6161 },
  'jagodina': { latitude: 43.9769, longitude: 21.2561 },
  'vršac': { latitude: 45.1167, longitude: 21.3000 },
  'novi pazar': { latitude: 43.1364, longitude: 20.5122 },

  // Alternative spellings and common variations
  'bg': { latitude: 44.7866, longitude: 20.4489 }, // Beograd shorthand
  'ns': { latitude: 45.2671, longitude: 19.8335 }, // Novi Sad shorthand
  'nis': { latitude: 43.3209, longitude: 21.8954 }, // Without diacritic
  'cacak': { latitude: 43.8914, longitude: 20.3497 }, // Without diacritic
  'krusevac': { latitude: 43.5800, longitude: 21.3278 }, // Without diacritic
  'uzice': { latitude: 43.8583, longitude: 19.8483 }, // Without diacritic
  'sabac': { latitude: 44.7472, longitude: 19.6997 }, // Without diacritic
  'zajecar': { latitude: 43.9042, longitude: 22.2883 }, // Without diacritic
  'pancevo': { latitude: 44.8708, longitude: 20.6408 }, // Without diacritic
};

/**
 * Calculate distance between two geographic points using Haversine formula
 * @param coord1 First coordinate
 * @param coord2 Second coordinate
 * @returns Distance in kilometers
 */
export function calculateDistance(coord1: Coordinates, coord2: Coordinates): number {
  const R = 6371; // Earth's radius in kilometers
  const dLat = toRadians(coord2.latitude - coord1.latitude);
  const dLon = toRadians(coord2.longitude - coord1.longitude);

  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRadians(coord1.latitude)) *
      Math.cos(toRadians(coord2.latitude)) *
      Math.sin(dLon / 2) *
      Math.sin(dLon / 2);

  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  const distance = R * c;

  return Math.round(distance * 10) / 10; // Round to 1 decimal place
}

/**
 * Convert degrees to radians
 */
function toRadians(degrees: number): number {
  return degrees * (Math.PI / 180);
}

/**
 * Get coordinates for a city name (case-insensitive, handles variations)
 * @param cityName City name to search for
 * @returns Coordinates if found, undefined otherwise
 */
export function getCityCoordinates(cityName: string): Coordinates | undefined {
  const normalized = cityName.toLowerCase().trim();

  // Direct match
  if (SERBIAN_CITIES[normalized]) {
    return SERBIAN_CITIES[normalized];
  }

  // Try to find partial match (e.g., "beograd centar" should match "beograd")
  const partialMatch = Object.keys(SERBIAN_CITIES).find(city =>
    normalized.includes(city) || city.includes(normalized)
  );

  if (partialMatch) {
    return SERBIAN_CITIES[partialMatch];
  }

  return undefined;
}

/**
 * Check if a car location is within radius of search location
 * @param carLocation Location string from car data
 * @param searchLocation Location string from search query
 * @param radiusKm Maximum distance in kilometers (default: 20)
 * @returns true if within radius, false otherwise
 */
export function isWithinRadius(
  carLocation: string,
  searchLocation: string,
  radiusKm: number = 20
): boolean {
  const carCoords = getCityCoordinates(carLocation);
  const searchCoords = getCityCoordinates(searchLocation);

  // If either location is unknown, fall back to exact string matching
  if (!carCoords || !searchCoords) {
    return carLocation.toLowerCase().includes(searchLocation.toLowerCase()) ||
           searchLocation.toLowerCase().includes(carLocation.toLowerCase());
  }

  const distance = calculateDistance(carCoords, searchCoords);
  return distance <= radiusKm;
}

/**
 * Get distance between two city names
 * @param location1 First city name
 * @param location2 Second city name
 * @returns Distance in km, or null if either city is unknown
 */
export function getDistanceBetweenCities(
  location1: string,
  location2: string
): number | null {
  const coord1 = getCityCoordinates(location1);
  const coord2 = getCityCoordinates(location2);

  if (!coord1 || !coord2) {
    return null;
  }

  return calculateDistance(coord1, coord2);
}

/**
 * Format distance for display in Serbian
 * @param distanceKm Distance in kilometers
 * @returns Formatted string (e.g., "5.2 km", "15 km")
 */
export function formatDistance(distanceKm: number): string {
  if (distanceKm < 1) {
    const meters = Math.round(distanceKm * 1000);
    return `${meters} m`;
  }

  if (distanceKm < 10) {
    return `${distanceKm.toFixed(1)} km`;
  }

  return `${Math.round(distanceKm)} km`;
}