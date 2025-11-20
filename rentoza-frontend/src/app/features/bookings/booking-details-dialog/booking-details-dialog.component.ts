import { Component, Inject, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { BookingService } from '@core/services/booking.service';
import { BookingDetails } from '@core/models/booking-details.model';
import { CarRules } from '@app/core/models/car-rules.model';

@Component({
  selector: 'app-booking-details-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatDividerModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatTooltipModule,
  ],
  templateUrl: './booking-details-dialog.component.html',
  styleUrls: ['./booking-details-dialog.component.scss'],
})
export class BookingDetailsDialogComponent implements OnInit {
  private readonly bookingService = inject(BookingService);
  private readonly dialogRef = inject(MatDialogRef<BookingDetailsDialogComponent>);

  booking = signal<BookingDetails | null>(null);
  isLoading = signal(true);
  error = signal<string | null>(null);

  constructor(@Inject(MAT_DIALOG_DATA) public data: { bookingId: number }) {}

  ngOnInit(): void {
    this.loadBookingDetails();
  }

  loadBookingDetails(): void {
    this.isLoading.set(true);
    this.error.set(null);

    this.bookingService.getBookingDetails(this.data.bookingId).subscribe({
      next: (details) => {
        this.booking.set(details);
        this.isLoading.set(false);
      },
      error: (err) => {
        console.error('Error loading booking details:', err);
        this.error.set('Nije moguće učitati detalje rezervacije.');
        this.isLoading.set(false);
      },
    });
  }

  close(): void {
    this.dialogRef.close();
  }

  openRules(): void {
    // Placeholder for Rules Modal
    alert('Pravila putovanja:\n\n' + CarRules.DEFAULT_RULES.join('\n• '));
  }

  openPhotos(): void {
    // Placeholder for Photos Modal
    alert('Galerija fotografija uskoro dostupna.');
  }

  openReceipt(): void {
    // Placeholder for Receipt Modal
    alert('Prikaz računa biće uskoro dostupan.');
  }

  getDuration(start: string, end: string): number {
    const startDate = new Date(start);
    const endDate = new Date(end);
    const diffTime = Math.abs(endDate.getTime() - startDate.getTime());
    return Math.ceil(diffTime / (1000 * 60 * 60 * 24));
  }

  formatDate(date: string): string {
    return new Date(date).toLocaleDateString('sr-RS', {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
    });
  }

  getStatusLabel(status: string): string {
    switch (status) {
      case 'PENDING_APPROVAL':
        return 'Na čekanju';
      case 'ACTIVE':
        return 'Aktivno';
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

  getStatusColor(status: string): string {
    switch (status) {
      case 'PENDING_APPROVAL':
        return 'warn';
      case 'ACTIVE':
        return 'primary';
      case 'COMPLETED':
        return 'accent';
      default:
        return '';
    }
  }
}
