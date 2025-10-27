import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';
import { Observable } from 'rxjs';

import { Booking } from '@core/models/booking.model';
import { BookingService } from '@core/services/booking.service';

@Component({
  selector: 'app-booking-history',
  standalone: true,
  imports: [CommonModule, MatCardModule, MatProgressSpinnerModule, FlexLayoutModule],
  templateUrl: './booking-history.component.html',
  styleUrls: ['./booking-history.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class BookingHistoryComponent {
  private readonly bookingService = inject(BookingService);

  readonly bookings$: Observable<Booking[]> = this.bookingService.getBookingHistory();

  trackByBookingId(_index: number, booking: Booking): string {
    return booking.id;
  }
}
