import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of, delay } from 'rxjs';

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
 * TODO: Backend Implementation Required
 * - Implement GET /api/admin/charts/revenue?months={months}&currencyCode={code}
 * - Implement GET /api/admin/charts/trips?weeks={weeks}
 * 
 * Current implementation uses mock data for development.
 * Replace mock data with actual HTTP calls when backend endpoints are ready.
 */
@Injectable({ providedIn: 'root' })
export class AdminChartsService {
  constructor(private http: HttpClient) {}

  /**
   * Fetches revenue chart data for the specified number of months
   * 
   * @param months Number of months to fetch (3, 6, or 12)
   * @returns Observable of revenue chart data
   * 
   * TODO: Replace mock data with actual API call:
   * return this.http.get<RevenueChartData>(`/api/admin/charts/revenue?months=${months}&currencyCode=RSD`);
   */
  getRevenueChart(months: number = 6): Observable<RevenueChartData> {
    // Mock data - replace with real API call when backend is ready
    const mockData = this.generateMockRevenueData(months);
    
    // Simulate network delay for realistic loading states
    return of(mockData).pipe(delay(800));
  }

  /**
   * Fetches trip activity data for the specified number of weeks
   * 
   * @param weeks Number of weeks to fetch (default: 6)
   * @returns Observable of trip activity data
   * 
   * TODO: Replace mock data with actual API call:
   * return this.http.get<TripActivityData>(`/api/admin/charts/trips?weeks=${weeks}`);
   */
  getTripActivity(weeks: number = 6): Observable<TripActivityData> {
    // Mock data - replace with real API call when backend is ready
    const mockData = this.generateMockTripData(weeks);
    
    // Simulate network delay for realistic loading states
    return of(mockData).pipe(delay(600));
  }

  /**
   * Generates mock revenue data for development
   * TODO: Remove this method when backend API is implemented
   */
  private generateMockRevenueData(months: number): RevenueChartData {
    const monthNames = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const currentMonth = new Date().getMonth();
    
    const labels: string[] = [];
    const totalRevenue: number[] = [];
    
    for (let i = months - 1; i >= 0; i--) {
      const monthIndex = (currentMonth - i + 12) % 12;
      labels.push(monthNames[monthIndex]);
      
      // Generate realistic-looking revenue data with growth trend
      const baseRevenue = 12000;
      const growthFactor = 1 + (months - i) * 0.08;
      const randomVariation = Math.random() * 3000;
      totalRevenue.push(Math.round(baseRevenue * growthFactor + randomVariation));
    }
    
    return {
      labels,
      totalRevenue,
      currencyCode: 'RSD'
    };
  }

  /**
   * Generates mock trip activity data for development
   * TODO: Remove this method when backend API is implemented
   */
  private generateMockTripData(weeks: number): TripActivityData {
    const labels: string[] = [];
    const completedTrips: number[] = [];
    const canceledTrips: number[] = [];
    
    const currentDate = new Date();
    const monthNames = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    
    for (let i = weeks - 1; i >= 0; i--) {
      const weekDate = new Date(currentDate);
      weekDate.setDate(weekDate.getDate() - (i * 7));
      
      const month = monthNames[weekDate.getMonth()];
      const weekOfMonth = Math.ceil(weekDate.getDate() / 7);
      labels.push(`${month} W${weekOfMonth}`);
      
      // Generate realistic trip data with upward trend
      const baseTrips = 45;
      const trendFactor = 1 + ((weeks - i) * 0.05);
      const randomVariation = Math.floor(Math.random() * 10);
      completedTrips.push(Math.round(baseTrips * trendFactor + randomVariation));
      
      // Canceled trips are typically much lower
      canceledTrips.push(Math.floor(Math.random() * 5) + 1);
    }
    
    return {
      labels,
      completedTrips,
      canceledTrips
    };
  }
}
