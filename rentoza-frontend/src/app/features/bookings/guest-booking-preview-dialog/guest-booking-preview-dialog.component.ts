import { Component, Inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { BookingService } from '@core/services/booking.service';
import { GuestBookingPreview } from '@core/models/guest-preview.model';
import { catchError, finalize } from 'rxjs/operators';
import { of } from 'rxjs';

@Component({
  selector: 'app-guest-booking-preview-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatDividerModule,
  ],
  templateUrl: './guest-booking-preview-dialog.component.html',
  styleUrls: ['./guest-booking-preview-dialog.component.scss'],
})
export class GuestBookingPreviewDialogComponent implements OnInit {
  guestPreview = signal<GuestBookingPreview | null>(null);
  isLoading = signal(true);
  error = signal<string | null>(null);

  constructor(
    private bookingService: BookingService,
    public dialogRef: MatDialogRef<GuestBookingPreviewDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: { bookingId: number }
  ) {}

  ngOnInit(): void {
    this.loadPreview();
  }

  loadPreview(): void {
    this.isLoading.set(true);
    this.error.set(null);

    this.bookingService
      .getGuestPreview(this.data.bookingId)
      .pipe(
        catchError((err) => {
          console.error('Error loading guest preview', err);
          if (err.status === 403) {
            this.error.set('Niste ovlašćeni da vidite ove informacije.');
          } else if (err.status === 404) {
            this.error.set('Rezervacija nije pronađena.');
          } else {
            this.error.set('Došlo je do greške prilikom učitavanja podataka.');
          }
          return of(null);
        }),
        finalize(() => this.isLoading.set(false))
      )
      .subscribe((preview) => {
        if (preview) {
          this.guestPreview.set(preview);
        }
      });
  }

  close(): void {
    this.dialogRef.close();
  }

  formatDate(dateStr: string): string {
    if (!dateStr) return '';
    return new Date(dateStr).toLocaleDateString('sr-RS', {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}
