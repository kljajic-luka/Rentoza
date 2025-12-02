import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterModule } from '@angular/router';
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
  private readonly router = inject(Router);

  protected readonly isLoading = signal(true);
  protected readonly bookings = signal<UserBooking[]>([]);

  protected readonly categorizedBookings = computed(() => {
    const now = new Date();
    const allBookings = this.bookings();

    return allBookings.reduce<CategorizedBookings>(
      (acc, booking) => {
        // Use startTime/endTime for exact timestamp architecture
        const startTime = new Date(booking.startTime);
        const endTime = new Date(booking.endTime);

        // Use unified completion check to determine if booking is completed
        if (isBookingCompleted(booking)) {
          acc.past.push(booking);
        } else if (now < startTime) {
          acc.upcoming.push(booking);
        } else {
          // Ongoing: start time has passed but booking not yet completed
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
    const startTime = new Date(booking.startTime);
    const endTime = new Date(booking.endTime);

    if (category === 'upcoming') {
      return `Počinje za ${this.getTimeUntil(startTime, now)}`;
    } else if (category === 'ongoing') {
      return `Preostalo vreme: ${this.getTimeUntil(endTime, now)}`;
    } else {
      return `Završeno pre ${this.getTimeSince(endTime, now)}`;
    }
  }

  private getTimeUntil(futureDate: Date, now: Date): string {
    const diffMs = futureDate.getTime() - now.getTime();
    const totalMinutes = Math.max(0, Math.floor(diffMs / (1000 * 60)));
    const days = Math.floor(totalMinutes / (60 * 24));
    const hours = Math.floor((totalMinutes - days * 24 * 60) / 60);
    const minutes = totalMinutes - days * 24 * 60 - hours * 60;

    if (days > 0) {
      return `${days} ${days === 1 ? 'dan' : 'dana'}`;
    }
    if (hours > 0) {
      const minutesPart = minutes > 0 ? ` i ${minutes} ${minutes === 1 ? 'minut' : 'minuta'}` : '';
      return `${hours} ${hours === 1 ? 'sat' : 'sati'}${minutesPart}`;
    }
    return `${minutes} ${minutes === 1 ? 'minut' : 'minuta'}`;
  }

  private getTimeSince(pastDate: Date, now: Date): string {
    const diffMs = now.getTime() - pastDate.getTime();
    const totalMinutes = Math.max(0, Math.floor(diffMs / (1000 * 60)));
    const days = Math.floor(totalMinutes / (60 * 24));
    const hours = Math.floor((totalMinutes - days * 24 * 60) / 60);
    const minutes = totalMinutes - days * 24 * 60 - hours * 60;

    if (days > 0) {
      return `${days} ${days === 1 ? 'dan' : 'dana'}`;
    }
    if (hours > 0) {
      const minutesPart = minutes > 0 ? ` i ${minutes} ${minutes === 1 ? 'minut' : 'minuta'}` : '';
      return `${hours} ${hours === 1 ? 'sat' : 'sati'}${minutesPart}`;
    }
    return `${minutes} ${minutes === 1 ? 'minut' : 'minuta'}`;
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
      case 'EXPIRED_SYSTEM':
        return 'Odobrenje isteklo';
      case 'CHECK_IN_OPEN':
        return 'Check-in otvoren';
      case 'CHECK_IN_HOST_COMPLETE':
        return 'Čeka vaš check-in';
      case 'CHECK_IN_COMPLETE':
        return 'Check-in završen';
      case 'IN_TRIP':
        return 'Putovanje u toku';
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
      case 'EXPIRED_SYSTEM':
        return 'status-expired';
      case 'CANCELLED':
        return 'status-cancelled';
      case 'COMPLETED':
        return 'status-completed';
      case 'CHECK_IN_OPEN':
      case 'CHECK_IN_HOST_COMPLETE':
        return 'status-check-in';
      case 'CHECK_IN_COMPLETE':
      case 'IN_TRIP':
        return 'status-active';
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
      case 'EXPIRED_SYSTEM':
        return 'Zahtev za rezervaciju je istekao jer domaćin nije odgovorio na vreme.';
      case 'CANCELLED':
        return 'Rezervacija je otkazana.';
      case 'COMPLETED':
        return 'Putovanje je završeno.';
      case 'CHECK_IN_OPEN':
        return 'Check-in je otvoren. Domaćin treba da prvi fotografiše vozilo.';
      case 'CHECK_IN_HOST_COMPLETE':
        return 'Domaćin je pripremio vozilo. Pregledajte fotografije i potvrdite stanje vozila.';
      case 'CHECK_IN_COMPLETE':
        return 'Check-in je završen. Možete preuzeti vozilo.';
      case 'IN_TRIP':
        return 'Putovanje je u toku. Uživajte!';
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
    return [
      'PENDING_APPROVAL',
      'DECLINED',
      'EXPIRED',
      'EXPIRED_SYSTEM',
      'CHECK_IN_HOST_COMPLETE',
    ].includes(status);
  }

  // ========== CHECK-IN WORKFLOW ==========

  /**
   * Check if guest can perform check-in for this booking.
   * Guest can check-in when host has completed their phase.
   */
  protected canGuestCheckIn(booking: UserBooking): boolean {
    return booking.status === 'CHECK_IN_HOST_COMPLETE';
  }

  /**
   * Navigate to guest check-in wizard.
   */
  protected goToGuestCheckIn(bookingId: number): void {
    this.router.navigate(['/bookings', bookingId, 'check-in']);
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
