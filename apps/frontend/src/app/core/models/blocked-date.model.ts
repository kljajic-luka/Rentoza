/**
 * Model representing a blocked date range for a car.
 * Blocked dates prevent renters from booking the car during those periods.
 */
export interface BlockedDate {
  id: number;
  carId: number;
  startDate: string; // ISO 8601 date format (YYYY-MM-DD)
  endDate: string;   // ISO 8601 date format (YYYY-MM-DD)
  createdAt?: string; // ISO 8601 timestamp
}

/**
 * Request DTO for blocking a date range
 */
export interface BlockDateRequest {
  carId: number;
  startDate: string; // ISO 8601 date format (YYYY-MM-DD)
  endDate: string;   // ISO 8601 date format (YYYY-MM-DD)
}