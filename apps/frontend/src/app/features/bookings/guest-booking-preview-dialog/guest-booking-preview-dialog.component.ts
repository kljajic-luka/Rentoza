import { Component, Inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { BookingService } from '@core/services/booking.service';
import { GuestBookingPreview, GuestTrustSignal } from '@core/models/guest-preview.model';
import { catchError, finalize } from 'rxjs/operators';
import { of } from 'rxjs';

/**
 * Guest booking preview dialog for host approval workflow.
 * Displays enterprise-grade guest information to help hosts make informed decisions.
 */
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
    MatTooltipModule,
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

  /**
   * Format date-time string for display (Serbian locale).
   */
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

  /**
   * Format license tenure in months to human-readable string.
   * e.g., 36 months -> "3 godine", 8 months -> "8 meseci"
   */
  formatTenure(months: number): string {
    if (!months || months <= 0) return '—';
    
    if (months >= 12) {
      const years = Math.floor(months / 12);
      const remainingMonths = months % 12;
      
      if (remainingMonths === 0) {
        return years === 1 ? '1 godina' : `${years} godina`;
      }
      return `${years}g ${remainingMonths}m`;
    }
    
    return months === 1 ? '1 mesec' : `${months} meseci`;
  }

  /**
   * Format license expiry date for display.
   */
  formatLicenseExpiry(dateStr: string): string {
    if (!dateStr) return '—';
    return new Date(dateStr).toLocaleDateString('sr-RS', {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
    });
  }

  trustSignalIcon(signal: GuestTrustSignal): string {
    switch (signal.code) {
      case 'DRIVER_APPROVED':
        return 'verified_user';
      case 'DRIVER_PENDING_REVIEW':
        return 'schedule';
      case 'DRIVER_REJECTED':
        return 'cancel';
      case 'DRIVER_SUSPENDED':
        return 'gpp_bad';
      case 'DRIVER_EXPIRED':
      case 'LICENSE_EXPIRING_SOON':
        return 'warning';
      case 'AGE_VERIFIED':
        return 'cake';
      default:
        return 'shield';
    }
  }

  trustSignalTooltip(signal: GuestTrustSignal): string {
    return signal.label;
  }
}