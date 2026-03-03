import { Injectable, inject } from '@angular/core';
import { Observable, of, forkJoin } from 'rxjs';
import { map, catchError } from 'rxjs/operators';
import {
  AdminApiService,
  AdminUserDto,
  AdminBookingDto,
  AdminCarDto,
} from '../../../../core/services/admin-api.service';

export interface SearchResultItem {
  type: 'user' | 'booking' | 'car';
  id: number;
  title: string;
  subtitle: string;
  icon: string;
  route: string[];
}

export interface SearchResults {
  users: SearchResultItem[];
  bookings: SearchResultItem[];
  cars: SearchResultItem[];
  total: number;
}

@Injectable({ providedIn: 'root' })
export class AdminSearchService {
  private api = inject(AdminApiService);

  search(query: string): Observable<SearchResults> {
    const q = query?.trim();
    if (!q || q.length < 2) {
      return of({ users: [], bookings: [], cars: [], total: 0 });
    }

    return forkJoin({
      users: this.api.getUsers(0, 5, q).pipe(
        map((res) =>
          res.content.map((u: AdminUserDto) => ({
            type: 'user' as const,
            id: u.id,
            title: `${u.firstName} ${u.lastName}`,
            subtitle: u.email,
            icon: 'person',
            route: ['/admin/users', String(u.id)],
          })),
        ),
        catchError(() => of([])),
      ),
      bookings: this.api.getBookings({ search: q, page: 0, size: 5 }).pipe(
        map((res) =>
          res.content.map((b: AdminBookingDto) => ({
            type: 'booking' as const,
            id: b.id,
            title: `Booking #${b.id} — ${b.carTitle}`,
            subtitle: `${b.renterName} · ${b.status}`,
            icon: 'event_note',
            route: ['/admin/bookings', String(b.id)],
          })),
        ),
        catchError(() => of([])),
      ),
      cars: this.api.getCars(0, 5, q).pipe(
        map((res) =>
          res.content.map((c: AdminCarDto) => ({
            type: 'car' as const,
            id: c.id,
            title: `${c.brand} ${c.model} (${c.year})`,
            subtitle: c.ownerName || c.ownerEmail,
            icon: 'directions_car',
            route: ['/admin/cars', String(c.id)],
          })),
        ),
        catchError(() => of([])),
      ),
    }).pipe(
      map((results) => ({
        ...results,
        total: results.users.length + results.bookings.length + results.cars.length,
      })),
    );
  }
}
