import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { finalize } from 'rxjs';

import { UserBooking } from '@core/models/booking.model';
import { BookingService } from '@core/services/booking.service';
import { isBookingCompleted } from '@core/utils/booking.utils';

type BookingCategory = 'upcoming' | 'ongoing' | 'past';

interface CategorizedBookings {
  upcoming: UserBooking[];
  ongoing: UserBooking[];
  past: UserBooking[];
}

@Component({
  selector: 'app-booking-history',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule
  ],
  templateUrl: './booking-history.component.html',
  styleUrls: ['./booking-history.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class BookingHistoryComponent {
  private readonly bookingService = inject(BookingService);

  protected readonly isLoading = signal(true);
  protected readonly bookings = signal<UserBooking[]>([]);

  protected readonly categorizedBookings = computed(() => {
    const now = new Date();
    const allBookings = this.bookings();

    return allBookings.reduce<CategorizedBookings>(
      (acc, booking) => {
        const startDate = new Date(booking.startDate);
        const endDate = new Date(booking.endDate);

        // Use unified completion check to determine if booking is completed
        if (isBookingCompleted(booking)) {
          acc.past.push(booking);
        } else if (now < startDate) {
          acc.upcoming.push(booking);
        } else {
          // Ongoing: start date has passed but booking not yet completed
          acc.ongoing.push(booking);
        }

        return acc;
      },
      { upcoming: [], ongoing: [], past: [] }
    );
  });

  constructor() {
    this.loadBookings();
  }

  private loadBookings(): void {
    this.isLoading.set(true);
    this.bookingService
      .getMyBookings()
      .pipe(finalize(() => this.isLoading.set(false)))
      .subscribe({
        next: (bookings) => this.bookings.set(bookings),
        error: (error) => console.error('Error loading bookings:', error),
      });
  }

  protected getTimeIndicator(booking: UserBooking, category: BookingCategory): string {
    const now = new Date();
    const startDate = new Date(booking.startDate);
    const endDate = new Date(booking.endDate);

    if (category === 'upcoming') {
      return `Počinje za ${this.getTimeUntil(startDate, now)}`;
    } else if (category === 'ongoing') {
      return `Preostalo vreme: ${this.getTimeUntil(endDate, now)}`;
    } else {
      return `Završeno pre ${this.getTimeSince(endDate, now)}`;
    }
  }

  private getTimeUntil(futureDate: Date, now: Date): string {
    const diffMs = futureDate.getTime() - now.getTime();
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));
    const diffHours = Math.floor(diffMs / (1000 * 60 * 60));

    if (diffDays > 0) {
      return `${diffDays} ${diffDays === 1 ? 'dan' : 'dana'}`;
    } else if (diffHours > 0) {
      return `${diffHours} ${diffHours === 1 ? 'sat' : 'sati'}`;
    } else {
      const diffMinutes = Math.floor(diffMs / (1000 * 60));
      return `${diffMinutes} ${diffMinutes === 1 ? 'minut' : 'minuta'}`;
    }
  }

  private getTimeSince(pastDate: Date, now: Date): string {
    const diffMs = now.getTime() - pastDate.getTime();
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));
    const diffHours = Math.floor(diffMs / (1000 * 60 * 60));

    if (diffDays > 0) {
      return `${diffDays} ${diffDays === 1 ? 'dan' : 'dana'}`;
    } else if (diffHours > 0) {
      return `${diffHours} ${diffHours === 1 ? 'sat' : 'sati'}`;
    } else {
      const diffMinutes = Math.floor(diffMs / (1000 * 60));
      return `${diffMinutes} ${diffMinutes === 1 ? 'minut' : 'minuta'}`;
    }
  }

  protected trackByBookingId(_index: number, booking: UserBooking): number {
    return booking.id;
  }
}
