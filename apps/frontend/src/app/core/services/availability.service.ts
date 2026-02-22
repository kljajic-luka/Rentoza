import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { shareReplay } from 'rxjs/operators';

import { environment } from '@environments/environment';
import { BlockedDate, BlockDateRequest } from '@core/models/blocked-date.model';

/**
 * Service for managing car availability blocking.
 * Provides methods for owners to block/unblock dates and for renters to check availability.
 */
@Injectable({ providedIn: 'root' })
export class AvailabilityService {
  private readonly baseUrl = `${environment.baseApiUrl}/availability`;

  // Cache for blocked dates by car ID
  private blockedDatesCache = new Map<number, Observable<BlockedDate[]>>();

  constructor(private readonly http: HttpClient) {}

  /**
   * Get all blocked dates for a specific car.
   * Results are cached with shareReplay(1) for performance.
   *
   * @param carId The ID of the car
   * @returns Observable of blocked date ranges
   */
  getBlockedDatesForCar(carId: number): Observable<BlockedDate[]> {
    if (!this.blockedDatesCache.has(carId)) {
      const request$ = this.http
        .get<BlockedDate[]>(`${this.baseUrl}/${carId}`, {
          withCredentials: true,
        })
        .pipe(shareReplay(1));

      this.blockedDatesCache.set(carId, request$);
    }

    return this.blockedDatesCache.get(carId)!;
  }

  /**
   * Block a date range for a car.
   * Only the car owner can perform this action (enforced by backend JWT validation).
   *
   * @param request The block date request
   * @returns Observable of the created blocked date range
   */
  blockDateRange(request: BlockDateRequest): Observable<BlockedDate> {
    return this.http.post<BlockedDate>(`${this.baseUrl}/block`, request, {
      withCredentials: true,
    });
  }

  /**
   * Unblock (delete) a specific blocked date range.
   * Only the car owner can perform this action (enforced by backend JWT validation).
   *
   * @param blockId The ID of the blocked date range to delete
   * @returns Observable that completes when the date range is unblocked
   */
  unblockDateRange(blockId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/block/${blockId}`, {
      withCredentials: true,
    });
  }

  /**
   * Clear the cache for a specific car.
   * Should be called after blocking or unblocking dates to force a fresh fetch.
   *
   * @param carId The ID of the car to clear cache for
   */
  clearCache(carId: number): void {
    this.blockedDatesCache.delete(carId);
  }

  /**
   * Clear all cached blocked dates.
   * Useful for logout or global refresh scenarios.
   */
  clearAllCache(): void {
    this.blockedDatesCache.clear();
  }
}