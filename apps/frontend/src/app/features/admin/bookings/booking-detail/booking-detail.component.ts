import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { AdminApiService, AdminBookingDto } from '../../../../core/services/admin-api.service';
import { AdminNotificationService } from '../../../../core/services/admin-notification.service';

@Component({
  selector: 'app-booking-detail',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatDividerModule,
  ],
  styleUrls: ['../../admin-shared.styles.scss'],
  template: `
    <div class="admin-page">
      <div style="display: flex; align-items: center; gap: 12px; margin-bottom: 24px;">
        <button mat-icon-button (click)="goBack()">
          <mat-icon>arrow_back</mat-icon>
        </button>
        <h1 class="page-title" style="margin: 0;">Booking #{{ bookingId }}</h1>
      </div>

      <div *ngIf="loading()" style="display: flex; justify-content: center; padding: 48px;">
        <mat-spinner diameter="48"></mat-spinner>
      </div>

      <div
        *ngIf="error()"
        class="error-banner"
        style="padding: 16px; margin-bottom: 16px; background: #fdecea; border-radius: 8px; color: #b71c1c;"
      >
        {{ error() }}
      </div>

      <div *ngIf="booking() as b" style="display: grid; grid-template-columns: 1fr 1fr; gap: 24px;">
        <mat-card>
          <mat-card-header>
            <mat-card-title>Booking Info</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <div class="detail-grid">
              <div class="detail-row">
                <span class="label">Status</span
                ><span class="value"
                  ><mat-chip [ngClass]="getStatusClass(b.status)">{{ b.status }}</mat-chip></span
                >
              </div>
              <div class="detail-row">
                <span class="label">Payment</span><span class="value">{{ b.paymentStatus }}</span>
              </div>
              <div class="detail-row">
                <span class="label">Total Price</span
                ><span class="value">{{ b.totalPrice | currency: 'RSD' : 'symbol-narrow' }}</span>
              </div>
              <div class="detail-row">
                <span class="label">Insurance</span
                ><span class="value">{{ b.insuranceType || 'None' }}</span>
              </div>
              <div class="detail-row">
                <span class="label">Trip Start</span
                ><span class="value">{{ b.startTime | date: 'medium' }}</span>
              </div>
              <div class="detail-row">
                <span class="label">Trip End</span
                ><span class="value">{{ b.endTime | date: 'medium' }}</span>
              </div>
              <div class="detail-row">
                <span class="label">Created</span
                ><span class="value">{{ b.createdAt | date: 'medium' }}</span>
              </div>
            </div>
          </mat-card-content>
        </mat-card>

        <mat-card>
          <mat-card-header>
            <mat-card-title>Parties</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <h3 style="margin: 16px 0 8px;">Car</h3>
            <div class="detail-grid">
              <div class="detail-row">
                <span class="label">Car</span><span class="value">{{ b.carTitle }}</span>
              </div>
              <div class="detail-row">
                <span class="label">Car ID</span><span class="value">#{{ b.carId }}</span>
              </div>
            </div>

            <mat-divider style="margin: 16px 0;"></mat-divider>

            <h3 style="margin: 0 0 8px;">Renter</h3>
            <div class="detail-grid">
              <div class="detail-row">
                <span class="label">Name</span><span class="value">{{ b.renterName }}</span>
              </div>
              <div class="detail-row">
                <span class="label">Email</span><span class="value">{{ b.renterEmail }}</span>
              </div>
            </div>

            <mat-divider style="margin: 16px 0;"></mat-divider>

            <h3 style="margin: 0 0 8px;">Owner</h3>
            <div class="detail-grid">
              <div class="detail-row">
                <span class="label">Name</span><span class="value">{{ b.ownerName }}</span>
              </div>
              <div class="detail-row">
                <span class="label">Email</span><span class="value">{{ b.ownerEmail }}</span>
              </div>
            </div>
          </mat-card-content>
        </mat-card>

        <mat-card *ngIf="!isTerminal(b.status)" style="grid-column: span 2;">
          <mat-card-header>
            <mat-card-title>Admin Actions</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <button mat-raised-button color="warn" (click)="forceComplete(b)">
              <mat-icon>check_circle</mat-icon>
              Force Complete
            </button>
          </mat-card-content>
        </mat-card>
      </div>
    </div>
  `,
  styles: [
    `
      .detail-grid {
        display: flex;
        flex-direction: column;
        gap: 8px;
        padding: 8px 0;
      }
      .detail-row {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }
      .label {
        color: rgba(0, 0, 0, 0.54);
        font-size: 14px;
      }
      .value {
        font-weight: 500;
      }
    `,
  ],
})
export class BookingDetailComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private adminApi = inject(AdminApiService);
  private notification = inject(AdminNotificationService);

  bookingId = 0;
  booking = signal<AdminBookingDto | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);

  ngOnInit() {
    this.bookingId = Number(this.route.snapshot.paramMap.get('id'));
    this.loadBooking();
  }

  loadBooking() {
    this.loading.set(true);
    this.error.set(null);
    this.adminApi.getBookingDetail(this.bookingId).subscribe({
      next: (b) => {
        this.booking.set(b);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err.error?.message || 'Failed to load booking');
        this.loading.set(false);
      },
    });
  }

  goBack() {
    this.router.navigate(['/admin/bookings']);
  }

  forceComplete(b: AdminBookingDto) {
    const reason = prompt('Enter reason for force-completion (min 10 chars):');
    if (!reason || reason.length < 10) {
      this.notification.showError('Reason must be at least 10 characters');
      return;
    }
    this.adminApi.forceCompleteBooking(b.id, reason).subscribe({
      next: () => {
        this.notification.showSuccess(`Booking #${b.id} force-completed`);
        this.loadBooking();
      },
      error: (err) => {
        this.notification.showError(err.error?.message || 'Failed to force-complete booking');
      },
    });
  }

  isTerminal(status: string): boolean {
    return [
      'COMPLETED',
      'CANCELLED',
      'DECLINED',
      'EXPIRED',
      'EXPIRED_SYSTEM',
      'NO_SHOW_HOST',
      'NO_SHOW_GUEST',
    ].includes(status);
  }

  getStatusClass(status: string): string {
    if (this.isTerminal(status)) return 'badge-neutral';
    if (status === 'IN_TRIP') return 'badge-success';
    if (status.startsWith('CHECK_IN') || status.startsWith('CHECKOUT')) return 'badge-warn';
    return 'badge-info';
  }
}
