import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog } from '@angular/material/dialog';
import { finalize } from 'rxjs';

import { UserBooking } from '@core/models/booking.model';
import { BookingService } from '@core/services/booking.service';
import { isBookingCompleted } from '@core/utils/booking.utils';
import { BookingDetailsDialogComponent } from '../../booking-details-dialog/booking-details-dialog.component';

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
    MatChipsModule,
    MatTooltipModule,
  ],
  templateUrl: './booking-history.component.html',
  styleUrls: ['./booking-history.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BookingHistoryComponent {
  private readonly bookingService = inject(BookingService);
  private readonly dialog = inject(MatDialog);

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

  // ========== PHASE 3: HOST APPROVAL WORKFLOW - STATUS BADGES ==========

  /**
   * Get human-readable status label for booking.
   *
   * Phase 3: Added support for new approval workflow statuses:
   * - PENDING_APPROVAL: "Čeka odobrenje"
   * - DECLINED: "Odbijeno"
   * - EXPIRED: "Isteklo"
   */
  protected getStatusLabel(status: string): string {
    switch (status) {
      case 'PENDING_APPROVAL':
        return 'Čeka odobrenje';
      case 'ACTIVE':
        return 'Aktivan';
      case 'DECLINED':
        return 'Odbijeno';
      case 'EXPIRED':
        return 'Isteklo';
      case 'CANCELLED':
        return 'Otkazano';
      case 'COMPLETED':
        return 'Završeno';
      default:
        return status;
    }
  }

  /**
   * Get CSS class for status badge styling.
   *
   * Phase 3: Added styling classes for new statuses:
   * - pending-approval: Yellow/orange (awaiting action)
   * - declined: Red (rejected)
   * - expired: Gray (timed out)
   */
  protected getStatusClass(status: string): string {
    switch (status) {
      case 'PENDING_APPROVAL':
        return 'status-pending-approval';
      case 'ACTIVE':
        return 'status-active';
      case 'DECLINED':
        return 'status-declined';
      case 'EXPIRED':
        return 'status-expired';
      case 'CANCELLED':
        return 'status-cancelled';
      case 'COMPLETED':
        return 'status-completed';
      default:
        return 'status-default';
    }
  }

  /**
   * Get tooltip description for status badge.
   *
   * Phase 3: Added tooltips explaining each status:
   * - PENDING_APPROVAL: Explains host must approve within deadline
   * - DECLINED: Shows decline reason if available
   * - EXPIRED: Explains automatic expiry after timeout
   */
  protected getStatusTooltip(booking: UserBooking): string {
    switch (booking.status) {
      case 'PENDING_APPROVAL':
        return 'Čekamo da domaćin odobri Vaš zahtev za rezervaciju. Bićete obavešteni o odluci.';
      case 'ACTIVE':
        return 'Rezervacija je aktivna i potvđena.';
      case 'DECLINED':
        return booking.hasOwnProperty('declineReason') && (booking as any).declineReason
          ? `Domaćin je odbio: ${(booking as any).declineReason}`
          : 'Domaćin je odbio Vaš zahtev za rezervaciju.';
      case 'EXPIRED':
        return 'Zahtev za rezervaciju je istekao jer domaćin nije odgovorio na vreme.';
      case 'CANCELLED':
        return 'Rezervacija je otkazana.';
      case 'COMPLETED':
        return 'Putovanje je završeno.';
      default:
        return booking.status;
    }
  }

  /**
   * Check if booking status should show additional info icon.
   *
   * Phase 3: Show info icon for PENDING_APPROVAL, DECLINED, EXPIRED
   * to indicate user should check tooltip for details.
   */
  protected shouldShowInfoIcon(status: string): boolean {
    return ['PENDING_APPROVAL', 'DECLINED', 'EXPIRED'].includes(status);
  }

  openDetails(id: number): void {
    this.dialog.open(BookingDetailsDialogComponent, {
      data: { bookingId: id },
      width: '600px',
      maxWidth: '95vw',
      maxHeight: '90vh',
      panelClass: 'booking-details-dialog-panel',
    });
  }
}
