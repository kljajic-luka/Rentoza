import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { filter, take } from 'rxjs';
import { MatTabsModule } from '@angular/material/tabs';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';

import { Booking } from '@core/models/booking.model';
import { BookingService } from '@core/services/booking.service';
import { AuthService } from '@core/auth/auth.service';
import { isBookingCompleted, canOwnerReviewRenter } from '@core/utils/booking.utils';
import {
  CancellationPreviewDialogComponent,
  CancellationPreviewDialogData,
  CancellationPreviewDialogResult,
} from '@shared/components/cancellation-preview-dialog/cancellation-preview-dialog.component';

@Component({
  selector: 'app-owner-bookings',
  standalone: true,
  imports: [
    CommonModule,
    MatTabsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatDialogModule,
    MatDividerModule,
  ],
  templateUrl: './owner-bookings.component.html',
  styleUrls: ['./owner-bookings.component.scss'],
})
export class OwnerBookingsComponent implements OnInit {
  private readonly snackBar = inject(MatSnackBar);
  private readonly bookingService = inject(BookingService);
  private readonly authService = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);

  /** Serbia timezone identifier for consistent time parsing */
  private readonly SERBIA_TIMEZONE = 'Europe/Belgrade';

  protected readonly isLoading = signal(false);
  protected readonly upcomingBookings = signal<Booking[]>([]);
  protected readonly activeBookings = signal<Booking[]>([]);
  protected readonly completedBookings = signal<Booking[]>([]);

  // Track which bookings are being processed (approve/decline) to prevent double-clicks
  protected readonly processingIds = signal<Set<number | string>>(new Set());

  protected readonly hasAnyBooking = computed(() => {
    return (
      this.upcomingBookings().length > 0 ||
      this.activeBookings().length > 0 ||
      this.completedBookings().length > 0
    );
  });

  ngOnInit(): void {
    this.loadOwnerBookings();
  }

  private loadOwnerBookings(): void {
    this.isLoading.set(true);

    this.authService.currentUser$
      .pipe(
        filter((user): user is NonNullable<typeof user> => {
          const isValid = user !== null && !!(user.email || user.id);
          return isValid;
        }),
        take(1),
      )
      .subscribe({
        next: (user) => {
          const email = user.email || user.id;
          this.bookingService.getOwnerBookings(email).subscribe({
            next: (bookings) => {
              // Defensive check: ensure bookings is an array
              if (!Array.isArray(bookings)) {
                console.warn('Received non-array bookings response:', bookings);
                this.upcomingBookings.set([]);
                this.activeBookings.set([]);
                this.completedBookings.set([]);
                this.isLoading.set(false);
                return;
              }

              // Group bookings by time-based logic (exact timestamp architecture)
              const now = new Date();

              const upcoming: Booking[] = [];
              const active: Booking[] = [];
              const completed: Booking[] = [];

              bookings.forEach((booking) => {
                // Defensive check: ensure booking has required properties
                // Using startTime/endTime for exact timestamp architecture
                if (!booking || !booking.status || !booking.startTime || !booking.endTime) {
                  console.warn('Invalid booking object:', booking);
                  return;
                }

                // Parse timestamps (exact timestamp architecture)
                const startTime = new Date(booking.startTime);
                const endTime = new Date(booking.endTime);

                // Categorize based on times AND status
                // 0. Terminal states (cancelled/declined/expired) should never appear as upcoming
                if (
                  ['CANCELLED', 'DECLINED', 'EXPIRED', 'EXPIRED_SYSTEM'].includes(booking.status)
                ) {
                  completed.push(booking);
                }
                // 1. Completed: End time has passed OR status indicates completion
                // BUT: Exclude checkout statuses (they need action even if endTime passed)
                else if (
                  (endTime < now || booking.status === 'COMPLETED') &&
                  !['CHECKOUT_OPEN', 'CHECKOUT_GUEST_COMPLETE', 'CHECKOUT_HOST_COMPLETE'].includes(
                    booking.status,
                  ) &&
                  booking.status !== 'CANCELLED' &&
                  booking.status !== 'DECLINED' &&
                  booking.status !== 'EXPIRED' &&
                  booking.status !== 'EXPIRED_SYSTEM'
                ) {
                  completed.push(booking);
                }
                // 2. Upcoming: Start time is in the future OR pending approval
                // BUT: If check-in is open, treat as active (better UX)
                else if (
                  (startTime > now || booking.status === 'PENDING_APPROVAL') &&
                  !['CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE'].includes(
                    booking.status,
                  ) &&
                  !['CHECKOUT_OPEN', 'CHECKOUT_GUEST_COMPLETE', 'CHECKOUT_HOST_COMPLETE'].includes(
                    booking.status,
                  )
                ) {
                  upcoming.push(booking);
                }
                // 3. Active: Current time is within booking period OR check-in/checkout is in progress
                else if (
                  (startTime <= now && endTime >= now) ||
                  ['CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE'].includes(
                    booking.status,
                  ) ||
                  ['CHECKOUT_OPEN', 'CHECKOUT_GUEST_COMPLETE', 'CHECKOUT_HOST_COMPLETE'].includes(
                    booking.status,
                  )
                ) {
                  active.push(booking);
                }
              });

              this.upcomingBookings.set(upcoming);
              this.activeBookings.set(active);
              this.completedBookings.set(completed);
              this.isLoading.set(false);
            },
            error: (error) => {
              console.error('Error loading owner bookings:', error);
              this.snackBar.open('Greška pri učitavanju rezervacija', 'Zatvori', {
                duration: 3000,
              });
              this.isLoading.set(false);
            },
          });
        },
        error: (error) => {
          console.error('Error getting user:', error);
          this.isLoading.set(false);
        },
      });
  }

  protected getStatusClass(status: string): string {
    switch (status) {
      case 'CONFIRMED':
      case 'ACTIVE':
        return 'status-confirmed';
      case 'CHECK_IN_OPEN':
      case 'CHECK_IN_HOST_COMPLETE':
      case 'CHECK_IN_COMPLETE':
        return 'status-check-in';
      case 'IN_TRIP':
        return 'status-active';
      case 'CHECKOUT_OPEN':
      case 'CHECKOUT_GUEST_COMPLETE':
      case 'CHECKOUT_HOST_COMPLETE':
        return 'status-checkout';
      case 'COMPLETED':
        return 'status-completed';
      case 'NO_SHOW_HOST':
      case 'NO_SHOW_GUEST':
      case 'CANCELLED':
        return 'status-cancelled';
      default:
        return '';
    }
  }

  protected getStatusLabel(status: string): string {
    switch (status) {
      case 'CONFIRMED':
        return 'Potvrđeno';
      case 'ACTIVE':
        return 'Aktivno';
      case 'COMPLETED':
        return 'Završeno';
      case 'CANCELLED':
        return 'Otkazano';
      case 'CHECK_IN_OPEN':
        return 'Check-in otvoren';
      case 'CHECK_IN_HOST_COMPLETE':
        return 'Čeka se gost';
      case 'CHECK_IN_COMPLETE':
        return 'Spreman za handshake';
      case 'IN_TRIP':
        return 'U toku';
      case 'CHECKOUT_OPEN':
        return 'Čeka se gost';
      case 'CHECKOUT_GUEST_COMPLETE':
        return 'Potvrdi checkout';
      case 'CHECKOUT_HOST_COMPLETE':
        return 'Checkout završen';
      case 'NO_SHOW_HOST':
        return 'Host nije se pojavio';
      case 'NO_SHOW_GUEST':
        return 'Gost nije se pojavio';
      case 'PENDING_APPROVAL':
        return 'Čeka odobrenje';
      default:
        return status;
    }
  }

  protected canReviewRenter(booking: Booking): boolean {
    // Use unified completion check to determine review eligibility
    return canOwnerReviewRenter(booking);
  }

  /**
   * Check if host can cancel a booking.
   * Only PENDING_APPROVAL and ACTIVE bookings are cancellable.
   */
  protected canCancel(booking: Booking): boolean {
    return booking.status === 'PENDING_APPROVAL' || booking.status === 'ACTIVE';
  }

  /**
   * Check if check-in is available for this booking.
   * Host can initiate check-in when booking status is in a check-in phase.
   * The booking.status field contains the workflow state (CHECK_IN_OPEN, CHECK_IN_HOST_COMPLETE, etc.)
   */
  protected canCheckIn(booking: Booking): boolean {
    // Check-in available if booking is in any check-in related status
    return (
      booking.status === 'CHECK_IN_OPEN' ||
      booking.status === 'CHECK_IN_HOST_COMPLETE' ||
      booking.status === 'CHECK_IN_COMPLETE'
    );
  }

  /**
   * Get check-in button label based on current booking status.
   */
  protected getCheckInLabel(booking: Booking): string {
    switch (booking.status) {
      case 'CHECK_IN_OPEN':
        return 'Započni Check-in';
      case 'CHECK_IN_HOST_COMPLETE':
        return 'Čeka se gost';
      case 'CHECK_IN_COMPLETE':
        return 'Završi Handshake';
      default:
        return 'Check-in';
    }
  }

  /**
   * Get check-in button icon based on current booking status.
   */
  protected getCheckInIcon(booking: Booking): string {
    switch (booking.status) {
      case 'CHECK_IN_OPEN':
        return 'photo_camera';
      case 'CHECK_IN_HOST_COMPLETE':
        return 'hourglass_empty';
      case 'CHECK_IN_COMPLETE':
        return 'handshake';
      default:
        return 'login';
    }
  }

  /**
   * Navigate to check-in wizard.
   */
  protected goToCheckIn(booking: Booking): void {
    this.router.navigate(['/bookings', booking.id, 'check-in']);
  }

  /**
   * Check if checkout is available for this booking.
   * Host can confirm checkout when guest has completed their part.
   */
  protected canCheckout(booking: Booking): boolean {
    return (
      booking.status === 'CHECKOUT_GUEST_COMPLETE' || booking.status === 'CHECKOUT_OPEN' // For viewing status
    );
  }

  /**
   * Get checkout button label based on current booking status.
   */
  protected getCheckoutLabel(booking: Booking): string {
    switch (booking.status) {
      case 'CHECKOUT_OPEN':
        return 'Čeka se gost';
      case 'CHECKOUT_GUEST_COMPLETE':
        return 'Potvrdi Checkout';
      default:
        return 'Checkout';
    }
  }

  /**
   * Get checkout button icon based on current booking status.
   */
  protected getCheckoutIcon(booking: Booking): string {
    switch (booking.status) {
      case 'CHECKOUT_OPEN':
        return 'hourglass_empty';
      case 'CHECKOUT_GUEST_COMPLETE':
        return 'check_circle';
      default:
        return 'logout';
    }
  }

  /**
   * Navigate to checkout wizard.
   */
  protected goToCheckout(booking: Booking): void {
    this.router.navigate(['/bookings', booking.id, 'checkout']);
  }

  /**
   * Open cancellation preview dialog for a booking.
   */
  protected cancelBooking(booking: Booking): void {
    const car = booking.car;
    const dialogData: CancellationPreviewDialogData = {
      bookingId: Number(booking.id),
      userRole: 'HOST',
      carInfo: car ? `${car.brand || ''} ${car.model}`.trim() : 'Vozilo',
      tripDates: `${new Date(booking.startTime).toLocaleDateString('sr-RS')} - ${new Date(
        booking.endTime,
      ).toLocaleDateString('sr-RS')}`,
    };

    const dialogRef = this.dialog.open(CancellationPreviewDialogComponent, {
      width: '500px',
      maxWidth: '95vw',
      disableClose: true,
      data: dialogData,
    });

    dialogRef.afterClosed().subscribe((result: CancellationPreviewDialogResult | undefined) => {
      if (result?.confirmed) {
        // Refresh bookings to reflect the cancellation
        this.loadOwnerBookings();
        this.snackBar.open('Rezervacija je uspešno otkazana.', 'Zatvori', {
          duration: 4000,
        });
      }
    });
  }

  protected reviewRenter(booking: Booking): void {
    // Navigate to full-page review (matching renter experience)
    this.router.navigate(['/owner/booking', booking.id, 'review']);
  }

  protected openBookingDetails(booking: Booking): void {
    import('../../dialogs/owner-booking-details-dialog/owner-booking-details-dialog.component').then(
      ({ OwnerBookingDetailsDialogComponent }) => {
        const dialogRef = this.dialog.open(OwnerBookingDetailsDialogComponent, {
          width: '800px',
          maxWidth: '95vw',
          maxHeight: '90vh',
          data: { bookingId: booking.id },
          panelClass: 'owner-booking-details-dialog-panel',
        });

        // Optional: Refresh bookings if actions were taken in dialog (e.g. cancellation)
        dialogRef.afterClosed().subscribe((result) => {
          if (result?.refresh) {
            this.loadOwnerBookings();
          }
        });
      },
    );
  }

  // Helper for template calculations
  protected getNetEarnings(totalPrice: number): number {
    // Platform fee is 20%
    return totalPrice * 0.8;
  }

  /**
   * Get human-readable time until a date (e.g., "58 minuta", "2 sata i 15 minuta").
   * Uses granular hours/minutes for same-day, days for future dates.
   * Parses dates as Serbia timezone since backend stores LocalDateTime without timezone.
   */
  protected getTimeUntil(dateStr: string): string {
    const date = this.parseAsSerbia(dateStr);
    const now = new Date();
    const diffMs = date.getTime() - now.getTime();

    if (diffMs < 0) return 'Završeno';

    const totalMinutes = Math.floor(diffMs / (1000 * 60));
    const days = Math.floor(totalMinutes / (60 * 24));
    const hours = Math.floor((totalMinutes - days * 24 * 60) / 60);
    const minutes = totalMinutes - days * 24 * 60 - hours * 60;

    // If more than 1 day away, show days
    if (days > 1) {
      return `za ${days} dana`;
    }

    // If tomorrow (1 day), but check if it's actually more than 24h
    if (days === 1) {
      // If it's really close to 24h, show "Sutra"
      if (hours === 0) return 'Sutra';
      return `za ${days} dan i ${hours} ${hours === 1 ? 'sat' : 'sati'}`;
    }

    // Same day - show hours and/or minutes
    if (hours > 0) {
      const minutesPart = minutes > 0 ? ` i ${minutes} min` : '';
      return `za ${hours} ${hours === 1 ? 'sat' : 'sata'}${minutesPart}`;
    }

    // Less than an hour
    if (minutes > 0) {
      return `za ${minutes} ${minutes === 1 ? 'minut' : 'minuta'}`;
    }

    return 'Sada';
  }

  /**
   * Parse a date string as Serbia timezone.
   * Backend sends LocalDateTime (no timezone) which represents Serbia local time.
   */
  private parseAsSerbia(dateStr: string): Date {
    // If the string already has timezone info, parse directly
    if (dateStr.includes('Z') || dateStr.includes('+') || dateStr.includes('-', 10)) {
      return new Date(dateStr);
    }
    // Parse as local time, then adjust for Serbia offset
    const utcDate = new Date(dateStr.replace('T', ' ').replace(/-/g, '/'));
    const serbiaOffset = this.getSerbiaOffsetMinutes(utcDate);
    // The dateStr represents Serbia local time, so subtract the offset to get UTC
    return new Date(utcDate.getTime() - serbiaOffset * 60 * 1000);
  }

  /**
   * Get Serbia's UTC offset in minutes for a given date (handles DST).
   */
  private getSerbiaOffsetMinutes(date: Date): number {
    const utcStr = date.toLocaleString('en-US', { timeZone: 'UTC' });
    const serbiaStr = date.toLocaleString('en-US', { timeZone: this.SERBIA_TIMEZONE });
    const utcTime = new Date(utcStr).getTime();
    const serbiaTime = new Date(serbiaStr).getTime();
    return (serbiaTime - utcTime) / (60 * 1000);
  }

  /**
   * Check if a booking is currently being processed (approve/decline).
   */
  protected isProcessing(bookingId: number | string): boolean {
    return this.processingIds().has(bookingId);
  }

  /**
   * Approve a pending booking request.
   */
  protected approveBooking(booking: Booking): void {
    const bookingId = typeof booking.id === 'string' ? parseInt(booking.id, 10) : booking.id;

    // Add to processing set to disable button
    this.processingIds.update((ids) => new Set(ids).add(bookingId));

    this.bookingService.approveBooking(bookingId).subscribe({
      next: () => {
        this.snackBar.open(
          `Rezervacija za ${booking.car.brand} ${booking.car.model} je odobrena!`,
          'Zatvori',
          {
            duration: 4000,
            panelClass: ['snackbar-success'],
          },
        );

        // Remove from processing set
        this.processingIds.update((ids) => {
          const newIds = new Set(ids);
          newIds.delete(bookingId);
          return newIds;
        });

        // Refresh bookings to update the list
        this.loadOwnerBookings();
      },
      error: (error) => {
        console.error('Error approving booking:', error);

        let errorMessage = 'Greška pri odobravanju rezervacije.';
        if (error.status === 403) {
          errorMessage = 'Nemate dozvolu za ovu akciju.';
        } else if (error.status === 404) {
          errorMessage = 'Rezervacija nije pronađena.';
        } else if (error.status === 409) {
          errorMessage = 'Rezervacija je već obrađena.';
        }

        this.snackBar.open(errorMessage, 'Zatvori', {
          duration: 5000,
          panelClass: ['snackbar-error'],
        });

        // Remove from processing set
        this.processingIds.update((ids) => {
          const newIds = new Set(ids);
          newIds.delete(bookingId);
          return newIds;
        });
      },
    });
  }

  /**
   * Decline a pending booking request with confirmation dialog.
   */
  protected declineBooking(booking: Booking): void {
    // Open decline dialog dynamically to avoid circular imports
    import('../../dialogs/decline-reason-dialog/decline-reason-dialog.component')
      .then(({ DeclineReasonDialogComponent }) => {
        const dialogRef = this.dialog.open(DeclineReasonDialogComponent, {
          width: '450px',
          maxWidth: '95vw',
          data: { booking },
        });

        dialogRef.afterClosed().subscribe((result: { reason: string } | null | undefined) => {
          if (result?.reason) {
            this.performDecline(booking, result.reason);
          }
        });
      })
      .catch(() => {
        // If dialog doesn't exist, perform simple decline with default reason
        this.performDecline(booking, 'Rezervacija je odbijena od strane vlasnika');
      });
  }

  /**
   * Actually perform the decline API call.
   */
  private performDecline(booking: Booking, reason?: string): void {
    const bookingId = typeof booking.id === 'string' ? parseInt(booking.id, 10) : booking.id;

    // Add to processing set
    this.processingIds.update((ids) => new Set(ids).add(bookingId));

    this.bookingService.declineBooking(bookingId, reason).subscribe({
      next: () => {
        this.snackBar.open(
          `Rezervacija za ${booking.car.brand} ${booking.car.model} je odbijena.`,
          'Zatvori',
          {
            duration: 4000,
            panelClass: ['snackbar-info'],
          },
        );

        // Remove from processing set
        this.processingIds.update((ids) => {
          const newIds = new Set(ids);
          newIds.delete(bookingId);
          return newIds;
        });

        // Refresh bookings
        this.loadOwnerBookings();
      },
      error: (error) => {
        console.error('Error declining booking:', error);

        let errorMessage = 'Greška pri odbijanju rezervacije.';
        if (error.status === 403) {
          errorMessage = 'Nemate dozvolu za ovu akciju.';
        } else if (error.status === 404) {
          errorMessage = 'Rezervacija nije pronađena.';
        }

        this.snackBar.open(errorMessage, 'Zatvori', {
          duration: 5000,
          panelClass: ['snackbar-error'],
        });

        // Remove from processing set
        this.processingIds.update((ids) => {
          const newIds = new Set(ids);
          newIds.delete(bookingId);
          return newIds;
        });
      },
    });
  }
}
