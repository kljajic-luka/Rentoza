import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { environment } from '../../../../../environments/environment';

/**
 * Data structure for revenue chart
 */
export interface RevenueChartData {
  labels: string[];
  totalRevenue: number[];
  currencyCode: string;
}

/**
 * Data structure for trip activity chart
 */
export interface TripActivityData {
  labels: string[];
  completedTrips: number[];
  canceledTrips: number[];
}

/**
 * Service for fetching admin dashboard chart data
 *
 * P1-5 FIX: Now uses real backend API instead of mock data.
 *
 * Backend Endpoints:
 * - GET /api/admin/charts/revenue?months={months}&currencyCode={code}
 * - GET /api/admin/charts/trips?weeks={weeks}
 */
@Injectable({ providedIn: 'root' })
export class AdminChartsService {
  private readonly apiUrl = environment.baseApiUrl || '';

  constructor(private http: HttpClient) {}

  /**
   * Fetches revenue chart data for the specified number of months
   *
   * @param months Number of months to fetch (3, 6, or 12)
   * @returns Observable of revenue chart data
   */
  getRevenueChart(months: number = 6): Observable<RevenueChartData> {
    return this.http
      .get<RevenueChartData>(`${this.apiUrl}/api/admin/charts/revenue`, {
        params: { months: months.toString(), currencyCode: 'RSD' },
      })
      .pipe(
        catchError((error) => {
          console.error('Failed to fetch revenue chart data:', error);
          // Return empty data on error to prevent UI crash
          return of({ labels: [], totalRevenue: [], currencyCode: 'RSD' });
        }),
      );
  }

  /**
   * Fetches trip activity data for the specified number of weeks
   *
   * @param weeks Number of weeks to fetch (default: 6)
   * @returns Observable of trip activity data
   */
  getTripActivity(weeks: number = 6): Observable<TripActivityData> {
    return this.http
      .get<TripActivityData>(`${this.apiUrl}/api/admin/charts/trips`, {
        params: { weeks: weeks.toString() },
      })
      .pipe(
        catchError((error) => {
          console.error('Failed to fetch trip activity data:', error);
          // Return empty data on error to prevent UI crash
          return of({ labels: [], completedTrips: [], canceledTrips: [] });
        }),
      );
  }
}
