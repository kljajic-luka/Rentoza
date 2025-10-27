import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '@environments/environment';
import { Booking, BookingRequest } from '@core/models/booking.model';

@Injectable({ providedIn: 'root' })
export class BookingService {
  private readonly baseUrl = `${environment.baseApiUrl}/bookings`;

  constructor(private readonly http: HttpClient) {}

  getBookingHistory(): Observable<Booking[]> {
    return this.http.get<Booking[]>(`${this.baseUrl}/me`);
  }

  createBooking(payload: BookingRequest): Observable<Booking> {
    return this.http.post<Booking>(this.baseUrl, payload);
  }
}
