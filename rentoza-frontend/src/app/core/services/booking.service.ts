import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '@environments/environment';
import { Booking, BookingRequest, UserBooking, BookingSlotDto } from '@core/models/booking.model';

@Injectable({ providedIn: 'root' })
export class BookingService {
  private readonly baseUrl = `${environment.baseApiUrl}/bookings`;

  constructor(private readonly http: HttpClient) {}

  getMyBookings(): Observable<UserBooking[]> {
    return this.http.get<UserBooking[]>(`${this.baseUrl}/me`, {
      withCredentials: true,
    });
  }

  getBookingHistory(): Observable<Booking[]> {
    return this.http.get<Booking[]>(`${this.baseUrl}/me`, {
      withCredentials: true,
    });
  }

  /**
   * Get full booking details for a car (OWNER/ADMIN only).
   *
   * Security:
   * - @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')") on backend
   * - Returns full booking DTOs with renter details, pricing, status
   * - Regular users (ROLE_USER) will receive 403 Forbidden
   *
   * Use Case:
   * - Owner dashboard: View all bookings for their cars with full details
   * - Admin dashboard: View all bookings with full details
   *
   * @param carId Car ID
   * @returns Observable<Booking[]> with full booking information
   */
  getBookingsForCar(carId: string): Observable<Booking[]> {
    return this.http.get<Booking[]>(`${this.baseUrl}/car/${carId}`, {
      withCredentials: true,
    });
  }

  /**
   * Get public-safe booking slots for a car (accessible to all users).
   *
   * Purpose:
   * - Calendar UI: Show which dates are booked/unavailable
   * - Minimal data exposure: only carId, startDate, endDate
   * - No PII: no renter, owner, or pricing information
   *
   * Security:
   * - @PreAuthorize("permitAll()") on backend
   * - Returns minimal BookingSlotDto (safe for public consumption)
   * - Rate-limited to 60 requests/minute
   *
   * Use Case:
   * - Car detail page: Show unavailable dates in booking calendar
   * - Renters (ROLE_USER): See which dates are taken
   * - Guests (unauthenticated): See which dates are taken
   *
   * @param carId Car ID
   * @returns Observable<BookingSlotDto[]> with only date ranges
   */
  getPublicBookingsForCar(carId: string | number): Observable<BookingSlotDto[]> {
    return this.http.get<BookingSlotDto[]>(`${this.baseUrl}/car/${carId}/public`, {
      withCredentials: true,
    });
  }

  createBooking(payload: BookingRequest): Observable<Booking> {
    return this.http.post<Booking>(this.baseUrl, payload, {
      withCredentials: true,
    });
  }

  /**
   * Phase 2.3: Validate booking availability before creating
   * Checks if the selected dates are available without persisting the booking
   *
   * @param payload Booking request with car ID and date range
   * @returns Observable with { available: boolean } or error response (409 on conflict)
   */
  validateBooking(payload: BookingRequest): Observable<{ available: boolean }> {
    return this.http.post<{ available: boolean }>(`${this.baseUrl}/validate`, payload, {
      withCredentials: true,
    });
  }

  /**
   * Get all bookings for owner's cars
   */
  getOwnerBookings(ownerEmail: string): Observable<Booking[]> {
    return this.http.get<Booking[]>(`${environment.baseApiUrl}/owner/bookings/${ownerEmail}`, {
      withCredentials: true,
    });
  }
}
