import { Component, Inject, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import {
  MAT_DIALOG_DATA,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatTabsModule } from '@angular/material/tabs';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';

import { BookingService } from '@core/services/booking.service';
import { Booking } from '@core/models/booking.model';

@Component({
  selector: 'app-owner-booking-details-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatDividerModule,
    MatTabsModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './owner-booking-details-dialog.component.html',
  styleUrls: ['./owner-booking-details-dialog.component.scss'],
})
export class OwnerBookingDetailsDialogComponent implements OnInit {
  private readonly bookingService = inject(BookingService);
  private readonly dialogRef = inject(MatDialogRef<OwnerBookingDetailsDialogComponent>);
  private readonly router = inject(Router);

  booking = signal<Booking | null>(null);
  isLoading = signal(true);
  error = signal<string | null>(null);

  // Financial Computations
  readonly durationDays = computed(() => {
    const b = this.booking();
    if (!b) return 0;
    const start = new Date(b.startTime);
    const end = new Date(b.endTime);
    const diffTime = Math.abs(end.getTime() - start.getTime());
    return Math.ceil(diffTime / (1000 * 60 * 60 * 24));
  });

  readonly grossTotal = computed(() => this.booking()?.totalPrice ?? 0);
  
  // Platform fee is typically 20% (hardcoded for now, should come from backend config)
  readonly platformFee = computed(() => this.grossTotal() * 0.2);
  
  readonly netEarnings = computed(() => this.grossTotal() - this.platformFee());

  // Privacy Logic: Show contact info only if booking is confirmed/active
  readonly showContactInfo = computed(() => {
    const status = this.booking()?.status;
    return status === 'ACTIVE' || status === 'IN_TRIP' || status === 'CHECK_IN_OPEN' || status === 'CHECK_IN_HOST_COMPLETE' || status === 'CHECK_IN_COMPLETE';
  });

  constructor(@Inject(MAT_DIALOG_DATA) public data: { bookingId: number }) {}

  ngOnInit(): void {
    this.loadBookingDetails();
  }

  loadBookingDetails(): void {
    this.isLoading.set(true);
    this.error.set(null);

    this.bookingService.getBookingById(this.data.bookingId).subscribe({
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

  sendMessage(): void {
    this.dialogRef.close();
    this.router.navigate(['/messages']);
  }

  getStatusLabel(status: string): string {
    switch (status) {
      case 'PENDING_APPROVAL': return 'Čeka odobrenje';
      case 'ACTIVE': return 'Aktivno';
      case 'DECLINED': return 'Odbijeno';
      case 'EXPIRED': return 'Isteklo';
      case 'CANCELLED': return 'Otkazano';
      case 'COMPLETED': return 'Završeno';
      case 'CHECK_IN_OPEN': return 'Check-in otvoren';
      case 'CHECK_IN_HOST_COMPLETE': return 'Čeka se gost';
      case 'CHECK_IN_COMPLETE': return 'Spreman za handshake';
      case 'IN_TRIP': return 'U toku';
      default: return status;
    }
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'PENDING_APPROVAL': return 'status-pending';
      case 'ACTIVE': 
      case 'IN_TRIP':
        return 'status-active';
      case 'COMPLETED': return 'status-completed';
      case 'CANCELLED':
      case 'DECLINED':
      case 'EXPIRED':
        return 'status-cancelled';
      case 'CHECK_IN_OPEN':
      case 'CHECK_IN_HOST_COMPLETE':
      case 'CHECK_IN_COMPLETE':
        return 'status-check-in';
      default: return '';
    }
  }

  // Timeline Helper
  isStepCompleted(step: 'created' | 'approved' | 'checkin' | 'started' | 'ended'): boolean {
    const b = this.booking();
    if (!b) return false;
    const status = b.status;

    switch (step) {
      case 'created': return true; // Always true if booking exists
      case 'approved': 
        return ['ACTIVE', 'IN_TRIP', 'COMPLETED', 'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE'].includes(status);
      case 'checkin':
        return ['IN_TRIP', 'COMPLETED', 'CHECK_IN_COMPLETE'].includes(status);
      case 'started':
        return ['IN_TRIP', 'COMPLETED'].includes(status);
      case 'ended':
        return status === 'COMPLETED';
      default: return false;
    }
  }
}
