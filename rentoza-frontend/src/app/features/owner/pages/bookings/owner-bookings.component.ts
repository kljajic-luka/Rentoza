import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTabsModule } from '@angular/material/tabs';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { Booking } from '@core/models/booking.model';

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
    MatSnackBarModule
  ],
  templateUrl: './owner-bookings.component.html',
  styleUrls: ['./owner-bookings.component.scss']
})
export class OwnerBookingsComponent implements OnInit {
  private readonly snackBar = inject(MatSnackBar);

  protected readonly isLoading = signal(false);
  protected readonly upcomingBookings = signal<Booking[]>([]);
  protected readonly activeBookings = signal<Booking[]>([]);
  protected readonly completedBookings = signal<Booking[]>([]);

  ngOnInit(): void {
    this.loadOwnerBookings();
  }

  private loadOwnerBookings(): void {
    this.isLoading.set(true);

    // TODO: Fetch from backend GET /api/bookings/owner/{email}
    setTimeout(() => {
      // Mock data for now
      this.upcomingBookings.set([]);
      this.activeBookings.set([]);
      this.completedBookings.set([]);
      this.isLoading.set(false);
    }, 500);
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
    // Can review if booking is completed and not already reviewed
    return booking.status === 'COMPLETED'; // TODO: Add check for existing review
  }

  protected reviewRenter(booking: Booking): void {
    // TODO: Navigate to owner review form with booking ID
    this.snackBar.open('Recenzija zakupca - u izradi', 'Zatvori', { duration: 2000 });
  }
}
