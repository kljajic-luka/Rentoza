import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { filter, take } from 'rxjs';
import { MatTabsModule } from '@angular/material/tabs';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';

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
  ],
  templateUrl: './owner-bookings.component.html',
  styleUrls: ['./owner-bookings.component.scss'],
})
export class OwnerBookingsComponent implements OnInit {
  private readonly snackBar = inject(MatSnackBar);
  private readonly bookingService = inject(BookingService);
  private readonly authService = inject(AuthService);
  private readonly dialog = inject(MatDialog);

  protected readonly isLoading = signal(false);
  protected readonly upcomingBookings = signal<Booking[]>([]);
  protected readonly activeBookings = signal<Booking[]>([]);
  protected readonly completedBookings = signal<Booking[]>([]);

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
        return 'status-confirmed';
      case 'ACTIVE':
        return 'status-active';
      case 'COMPLETED':
        return 'status-completed';
      default:
        return '';
    }
  }

  protected getStatusLabel(status: string): string {
    switch (status) {
      case 'CONFIRMED':
        return 'Potvrđeno';
      case 'ACTIVE':
        return 'U toku';
      case 'COMPLETED':
        return 'Završeno';
      case 'CANCELLED':
        return 'Otkazano';
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
}
