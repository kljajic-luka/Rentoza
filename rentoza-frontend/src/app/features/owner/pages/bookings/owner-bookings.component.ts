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
    MatDialogModule
  ],
  templateUrl: './owner-bookings.component.html',
  styleUrls: ['./owner-bookings.component.scss']
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

              // Group bookings using unified completion logic
              const now = new Date();
              const upcoming: Booking[] = [];
              const active: Booking[] = [];
              const completed: Booking[] = [];

              bookings.forEach((booking) => {
                // Defensive check: ensure booking has required properties
                if (!booking || !booking.status || !booking.endDate) {
                  console.warn('Invalid booking object:', booking);
                  return;
                }

                // Use unified completion check to categorize bookings
                if (isBookingCompleted(booking)) {
                  completed.push(booking);
                } else if (booking.status === 'CONFIRMED') {
                  upcoming.push(booking);
                } else if (booking.status === 'ACTIVE') {
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
              this.snackBar.open('Greška pri učitavanju rezervacija', 'Zatvori', { duration: 3000 });
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

  protected reviewRenter(booking: Booking): void {
    const dialogRef = this.dialog.open(OwnerReviewDialogComponent, {
      width: '700px',
      maxWidth: '95vw',
      maxHeight: '90vh',
      data: { booking },
      disableClose: false
    });

    dialogRef.afterClosed().subscribe((success: boolean) => {
      if (success) {
        // Refresh bookings to update the hasOwnerReview flag
        this.loadOwnerBookings();
      }
    });
  }
}
