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
import { OwnerReviewDialogComponent } from '@features/owner/dialogs/owner-review-dialog/owner-review-dialog.component';
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

  protected readonly isLoading = signal(false);
  protected readonly upcomingBookings = signal<Booking[]>([]);
  protected readonly activeBookings = signal<Booking[]>([]);
  protected readonly completedBookings = signal<Booking[]>([]);

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
        take(1)
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

              // Group bookings by date-based logic
              const today = new Date();
              today.setHours(0, 0, 0, 0); // Normalize to midnight for accurate comparison

              const upcoming: Booking[] = [];
              const active: Booking[] = [];
              const completed: Booking[] = [];

              bookings.forEach((booking) => {
                // Defensive check: ensure booking has required properties
                if (!booking || !booking.status || !booking.startDate || !booking.endDate) {
                  console.warn('Invalid booking object:', booking);
                  return;
                }

                // Parse and normalize dates
                const startDate = new Date(booking.startDate);
                const endDate = new Date(booking.endDate);
                startDate.setHours(0, 0, 0, 0);
                endDate.setHours(0, 0, 0, 0);

                // Categorize based on dates AND status
                // 1. Completed: End date has passed OR status indicates completion
                if (
                  endDate < today ||
                  booking.status === 'COMPLETED' ||
                  booking.status === 'CANCELLED' ||
                  booking.status === 'DECLINED' ||
                  booking.status === 'EXPIRED'
                ) {
                  completed.push(booking);
                }
                // 2. Upcoming: Start date is in the future OR pending approval
                else if (startDate > today || booking.status === 'PENDING_APPROVAL') {
                  upcoming.push(booking);
                }
                // 3. Active: Current date is within booking period (startDate <= today <= endDate)
                else if (startDate <= today && endDate >= today) {
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
   * Open cancellation preview dialog for a booking.
   */
  protected cancelBooking(booking: Booking): void {
    const car = booking.car;
    const dialogData: CancellationPreviewDialogData = {
      bookingId: Number(booking.id),
      userRole: 'HOST',
      carInfo: car ? `${car.brand || ''} ${car.model}`.trim() : 'Vozilo',
      tripDates: `${new Date(booking.startDate).toLocaleDateString('sr-RS')} - ${new Date(
        booking.endDate
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
    const dialogRef = this.dialog.open(OwnerReviewDialogComponent, {
      width: '700px',
      maxWidth: '95vw',
      maxHeight: '90vh',
      data: { booking },
      disableClose: false,
    });

    dialogRef.afterClosed().subscribe((success: boolean) => {
      if (success) {
        // Refresh bookings to update the hasOwnerReview flag
        this.loadOwnerBookings();
      }
    });
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
      }
    );
  }

  // Helper for template calculations
  protected getNetEarnings(totalPrice: number): number {
    // Platform fee is 20%
    return totalPrice * 0.8;
  }

  protected getTimeUntil(dateStr: string): string {
    const date = new Date(dateStr);
    const now = new Date();
    const diffMs = date.getTime() - now.getTime();
    const days = Math.ceil(diffMs / (1000 * 60 * 60 * 24));
    
    if (days < 0) return 'Završeno';
    if (days === 0) return 'Danas';
    if (days === 1) return 'Sutra';
    return `za ${days} dana`;
  }
}
