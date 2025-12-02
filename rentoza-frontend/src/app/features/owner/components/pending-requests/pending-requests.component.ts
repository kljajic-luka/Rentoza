import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';

import { Booking } from '@core/models/booking.model';
import { BookingService } from '@core/services/booking.service';
import { DeclineReasonDialogComponent } from '../../dialogs/decline-reason-dialog/decline-reason-dialog.component';
import { GuestBookingPreviewDialogComponent } from '@features/bookings/guest-booking-preview-dialog/guest-booking-preview-dialog.component';
import { CountdownTimerComponent } from '@shared/components/countdown-timer/countdown-timer.component';

/**
 * Pending Requests Component - Host Approval Workflow (Phase 3)
 *
 * Purpose:
 * - Display pending booking approval requests for owner's cars
 * - Allow owner to approve or decline requests
 * - Show countdown timer for decision deadline
 * - Real-time status updates after approval/decline
 *
 * Security:
 * - Backend enforces RLS: Only returns bookings for authenticated owner's cars
 * - Approve/decline endpoints require ROLE_OWNER or ROLE_ADMIN
 * - @PreAuthorize + @bookingSecurity.canDecide() enforcement
 *
 * UX:
 * - Approve button: Green, primary action
 * - Decline button: Red, with reason dialog
 * - Empty state: "No pending requests" message
 * - Loading state: Spinner during data fetch
 * - Error handling: User-friendly messages for 403/404/409
 */
@Component({
  selector: 'app-pending-requests',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatDialogModule,
    MatTooltipModule,
    CountdownTimerComponent,
  ],
  templateUrl: './pending-requests.component.html',
  styleUrls: ['./pending-requests.component.scss'],
})
export class PendingRequestsComponent implements OnInit {
  private readonly bookingService = inject(BookingService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  protected readonly isLoading = signal(true);
  protected readonly pendingBookings = signal<Booking[]>([]);
  protected readonly processingIds = signal<Set<number | string>>(new Set());

  ngOnInit(): void {
    this.loadPendingRequests();
  }

  private loadPendingRequests(): void {
    this.isLoading.set(true);
    this.bookingService.getPendingOwnerBookings().subscribe({
      next: (bookings) => {
        this.pendingBookings.set(bookings);
        this.isLoading.set(false);
      },
      error: (error) => {
        console.error('Error loading pending requests:', error);
        this.snackBar.open('Greška pri učitavanju zahteva. Pokušajte ponovo.', 'Zatvori', {
          duration: 5000,
          panelClass: ['snackbar-error'],
        });
        this.isLoading.set(false);
      },
    });
  }

  protected approveBooking(booking: Booking): void {
    const bookingId = typeof booking.id === 'string' ? parseInt(booking.id, 10) : booking.id;

    // Add to processing set to disable button
    this.processingIds.update((ids) => new Set(ids).add(bookingId));

    this.bookingService.approveBooking(bookingId).subscribe({
      next: (updatedBooking) => {
        this.snackBar.open(
          `Rezervacija za ${booking.car.model} ${booking.car.model} je odobrena!`,
          'Zatvori',
          {
            duration: 4000,
            panelClass: ['snackbar-success'],
          }
        );

        // Remove from pending list
        this.pendingBookings.update((bookings) => bookings.filter((b) => b.id !== booking.id));

        // Remove from processing set
        this.processingIds.update((ids) => {
          const newIds = new Set(ids);
          newIds.delete(bookingId);
          return newIds;
        });
      },
      error: (error) => {
        console.error('Error approving booking:', error);

        let errorMessage = 'Greška pri odobravanju rezervacije.';
        if (error.status === 403) {
          errorMessage = 'Nemate dozvolu za ovu akciju.';
        } else if (error.status === 404) {
          errorMessage = 'Rezervacija nije pronađena.';
        } else if (error.status === 409) {
          errorMessage =
            error.error?.error ||
            'Konflikt: Datumi su već zauzeti ili rezervacija je već obrađena.';
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

        // Refresh list in case of conflicts
        if (error.status === 409) {
          this.loadPendingRequests();
        }
      },
    });
  }

  protected declineBooking(booking: Booking): void {
    const bookingId = typeof booking.id === 'string' ? parseInt(booking.id, 10) : booking.id;

    // Open dialog to get decline reason
    const dialogRef = this.dialog.open(DeclineReasonDialogComponent, {
      width: '500px',
      data: { booking },
      disableClose: false, // Allow ESC/backdrop to close (treated as cancel)
    });

    dialogRef.afterClosed().subscribe((result: { reason: string } | null | undefined) => {
      // Strict check: Only proceed if we have a result object with a truthy reason
      // This filters out:
      // - null (Cancel button)
      // - undefined (Backdrop click / ESC)
      // - empty reason (shouldn't happen due to dialog validation, but safe to check)
      if (result && result.reason) {
        const reason = result.reason;

        // User confirmed decline with reason
        this.processingIds.update((ids) => new Set(ids).add(bookingId));

        this.bookingService.declineBooking(bookingId, reason).subscribe({
          next: () => {
            this.snackBar.open(
              `Rezervacija za ${booking.car.model} ${booking.car.model} je odbijena.`,
              'Zatvori',
              {
                duration: 4000,
                panelClass: ['snackbar-info'],
              }
            );

            // Remove from pending list
            this.pendingBookings.update((bookings) => bookings.filter((b) => b.id !== booking.id));

            this.processingIds.update((ids) => {
              const newIds = new Set(ids);
              newIds.delete(bookingId);
              return newIds;
            });
          },
          error: (error) => {
            console.error('Error declining booking:', error);

            let errorMessage = 'Greška pri odbijanju rezervacije.';
            if (error.status === 403) {
              errorMessage = 'Nemate dozvolu za ovu akciju.';
            } else if (error.status === 404) {
              errorMessage = 'Rezervacija nije pronađena.';
            } else if (error.status === 409) {
              errorMessage = 'Konflikt: Rezervacija je već obrađena.';
            }

            this.snackBar.open(errorMessage, 'Zatvori', {
              duration: 5000,
              panelClass: ['snackbar-error'],
            });

            this.processingIds.update((ids) => {
              const newIds = new Set(ids);
              newIds.delete(bookingId);
              return newIds;
            });
          },
        });
      }
    });
  }

  protected isProcessing(bookingId: string | number): boolean {
    const id = typeof bookingId === 'string' ? parseInt(bookingId, 10) : bookingId;
    return this.processingIds().has(id);
  }

  protected getDeadlineTimeLeft(deadlineAt?: string): string {
    if (!deadlineAt) return '';

    const deadline = new Date(deadlineAt);
    const now = new Date();
    const diff = deadline.getTime() - now.getTime();

    if (diff <= 0) return 'Isteklo';

    const hours = Math.floor(diff / (1000 * 60 * 60));
    const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));

    if (hours > 24) {
      const days = Math.floor(hours / 24);
      return `${days} dan${days > 1 ? 'a' : ''}`;
    }

    return `${hours}h ${minutes}m`;
  }

  protected getDeadlineClass(deadlineAt?: string): string {
    if (!deadlineAt) return '';

    const deadline = new Date(deadlineAt);
    const now = new Date();
    const hoursLeft = (deadline.getTime() - now.getTime()) / (1000 * 60 * 60);

    if (hoursLeft <= 0) return 'expired';
    if (hoursLeft <= 6) return 'urgent';
    if (hoursLeft <= 24) return 'warning';
    return 'normal';
  }

  protected formatDate(dateString: string): string {
    const date = new Date(dateString);
    return date.toLocaleDateString('sr-RS', {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
    });
  }

  /**
   * Format datetime string for display (dd.MM.yyyy HH:mm)
   * Exact Timestamp Architecture support
   */
  protected formatDateTime(dateTimeString: string): string {
    const date = new Date(dateTimeString);
    return date.toLocaleDateString('sr-RS', {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  protected refreshList(): void {
    this.loadPendingRequests();
  }

  protected openGuestPreview(booking: Booking): void {
    const bookingId = typeof booking.id === 'string' ? parseInt(booking.id, 10) : booking.id;

    this.dialog.open(GuestBookingPreviewDialogComponent, {
      width: '500px',
      data: { bookingId },
      autoFocus: false,
    });
  }
}
