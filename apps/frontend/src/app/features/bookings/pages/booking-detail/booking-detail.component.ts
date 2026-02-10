/**
 * Booking Detail Page Component
 *
 * Displays full booking details and provides access to check-in workflow.
 */
import {
  Component,
  OnInit,
  inject,
  signal,
  computed,
  ChangeDetectionStrategy,
  DestroyRef,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatDividerModule } from '@angular/material/divider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';

import { BookingService } from '../../../../core/services/booking.service';
import { BookingDetails, PickupLocationData } from '../../../../core/models/booking-details.model';
import { ReadOnlyPickupLocationComponent } from '../../components/readonly-pickup-location';

@Component({
  selector: 'app-booking-detail',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatDividerModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    ReadOnlyPickupLocationComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="booking-detail-page">
      <!-- Loading -->
      @if (isLoading()) {
      <div class="loading-container">
        <mat-spinner diameter="48"></mat-spinner>
        <p>Učitavanje rezervacije...</p>
      </div>
      }

      <!-- Error -->
      @if (error()) {
      <div class="error-container">
        <mat-icon>error</mat-icon>
        <h2>Greška</h2>
        <p>{{ error() }}</p>
        <button mat-raised-button color="primary" (click)="loadBooking()">Pokušaj ponovo</button>
      </div>
      }

      <!-- Content -->
      @if (booking() && !isLoading()) {
      <header class="page-header">
        <button mat-icon-button (click)="goBack()">
          <mat-icon>arrow_back</mat-icon>
        </button>
        <h1>Rezervacija #{{ booking()?.id }}</h1>
      </header>

      <!-- Status badge -->
      <mat-chip-set class="status-chips">
        <mat-chip [class]="'status-' + booking()?.status?.toLowerCase()">
          {{ getStatusLabel(booking()?.status) }}
        </mat-chip>
      </mat-chip-set>

      <!-- Live countdown banner -->
      @if (countdownText()) {
      <div class="countdown-banner" [class.checkout-prep]="isCheckoutPrep()">
        <mat-icon>{{ countdownIcon() }}</mat-icon>
        <span>{{ countdownText() }}</span>
      </div>
      }

      <!-- Car info -->
      <mat-card class="car-card">
        @if (booking()?.primaryImageUrl) {
        <img [src]="booking()?.primaryImageUrl" [alt]="carTitle()" class="car-image" />
        }
        <mat-card-content>
          <h3>{{ carTitle() }}</h3>
          <p class="car-owner">
            <mat-icon>person</mat-icon>
            {{ booking()?.hostName || 'Domaćin' }}
          </p>
        </mat-card-content>
      </mat-card>

      <!-- Dates -->
      <mat-card class="dates-card">
        <mat-card-content>
          <div class="date-row">
            <mat-icon>event</mat-icon>
            <div>
              <span class="label">Od</span>
              <span class="value">{{ booking()?.startTime | date : 'dd.MM.yyyy HH:mm' }}</span>
            </div>
          </div>
          <mat-divider></mat-divider>
          <div class="date-row">
            <mat-icon>event</mat-icon>
            <div>
              <span class="label">Do</span>
              <span class="value">{{ booking()?.endTime | date : 'dd.MM.yyyy HH:mm' }}</span>
            </div>
          </div>
        </mat-card-content>
      </mat-card>

      <!-- Pickup Location -->
      @if (pickupLocationData()) {
      <div class="pickup-location-section">
        <app-readonly-pickup-location
          [pickupLocation]="pickupLocationData()"
          [showDeliveryInfo]="hasDeliveryInfo()"
          [deliveryDistance]="booking()?.deliveryDistanceKm ?? null"
          [deliveryFee]="booking()?.deliveryFeeCalculated ?? null"
          mode="standard"
        />
      </div>
      }

      <!-- Pricing -->
      <mat-card class="pricing-card">
        <mat-card-content>
          <h4>Cena</h4>
          <div class="price-row">
            <span>Ukupno</span>
            <span class="price">{{ booking()?.totalPrice | currency : 'EUR' }}</span>
          </div>
        </mat-card-content>
      </mat-card>

      <!-- Actions -->
      <div class="actions">
        @if (canCheckIn()) {
        <button
          mat-raised-button
          color="primary"
          [routerLink]="['/bookings', booking()?.id, 'check-in']"
          class="check-in-button"
        >
          <mat-icon>login</mat-icon>
          Započni Check-in
        </button>
        } @if (canReview()) {
        <button
          mat-stroked-button
          color="accent"
          [routerLink]="['/bookings', booking()?.id, 'review']"
        >
          <mat-icon>rate_review</mat-icon>
          Ostavi recenziju
        </button>
        }

        <button mat-stroked-button [routerLink]="['/messages']">
          <mat-icon>chat</mat-icon>
          Poruke
        </button>
      </div>
      }
    </div>
  `,
  styles: [
    `
      .booking-detail-page {
        padding: 16px;
        max-width: 600px;
        margin: 0 auto;
      }

      .loading-container,
      .error-container {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        min-height: 300px;
        gap: 16px;
        text-align: center;
      }

      .error-container mat-icon {
        font-size: 64px;
        width: 64px;
        height: 64px;
        color: var(--warn-color, #f44336);
      }

      .page-header {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 16px;
      }

      .page-header h1 {
        margin: 0;
        font-size: 20px;
      }

      .status-chips {
        margin-bottom: 16px;
      }

      .countdown-banner {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 12px 16px;
        border-radius: 12px;
        background: rgba(59, 130, 246, 0.12);
        color: #1d4ed8;
        font-weight: 600;
        font-size: 15px;
        margin-bottom: 16px;
      }

      .countdown-banner mat-icon {
        font-size: 20px;
        width: 20px;
        height: 20px;
      }

      .countdown-banner.checkout-prep {
        background: rgba(245, 124, 0, 0.14);
        color: #e65100;
        animation: pulse-detail 2s ease-in-out infinite;
      }

      @keyframes pulse-detail {
        0%, 100% { opacity: 1; }
        50% { opacity: 0.78; }
      }

      .status-confirmed {
        background: var(--success-bg, #e8f5e9) !important;
        color: var(--success-color, #4caf50) !important;
      }

      .status-pending {
        background: var(--warn-bg, #fff3e0) !important;
        color: var(--warn-color, #ff9800) !important;
      }

      .status-active,
      .status-in_trip {
        background: var(--info-bg, #e3f2fd) !important;
        color: var(--info-color, #1976d2) !important;
      }

      .status-completed {
        background: #f5f5f5 !important;
        color: #757575 !important;
      }

      mat-card {
        margin-bottom: 16px;
      }

      .car-card .car-image {
        width: 100%;
        height: 180px;
        object-fit: cover;
      }

      .car-card h3 {
        margin: 12px 0 8px;
      }

      .car-owner {
        display: flex;
        align-items: center;
        gap: 4px;
        color: var(--text-secondary, #757575);
        font-size: 14px;
        margin: 0;
      }

      .car-owner mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }

      .dates-card mat-card-content {
        padding: 0 !important;
      }

      .date-row {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 16px;
      }

      .date-row mat-icon {
        color: var(--primary-color, #1976d2);
      }

      .date-row .label {
        display: block;
        font-size: 12px;
        color: var(--text-secondary, #757575);
      }

      .date-row .value {
        font-weight: 500;
      }

      .pricing-card h4 {
        margin: 0 0 12px;
      }

      .price-row {
        display: flex;
        justify-content: space-between;
        align-items: center;
      }

      .price {
        font-size: 20px;
        font-weight: bold;
        color: var(--primary-color, #1976d2);
      }

      .actions {
        display: flex;
        flex-direction: column;
        gap: 12px;
        margin-top: 24px;
      }

      .check-in-button {
        height: 48px;
      }
    `,
  ],
})
export class BookingDetailComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private bookingService = inject(BookingService);
  private destroyRef = inject(DestroyRef);

  booking = signal<BookingDetails | null>(null);
  isLoading = signal(false);
  error = signal<string | null>(null);

  /** Live ticker — bumps every 60s for countdown reactivity */
  private tick = signal(0);
  private tickIntervalId: ReturnType<typeof setInterval> | null = null;

  carTitle = computed(() => {
    const b = this.booking();
    if (!b) return 'Vozilo';
    return `${b.brand} ${b.model}${b.year ? ` (${b.year})` : ''}`;
  });

  /**
   * Computed: Pickup location data for display component.
   */
  pickupLocationData = computed<PickupLocationData | null>(() => {
    const b = this.booking();
    if (!b || !b.pickupLatitude || !b.pickupLongitude) return null;

    return {
      latitude: b.pickupLatitude,
      longitude: b.pickupLongitude,
      address: b.pickupAddress,
      city: b.pickupCity,
      zipCode: b.pickupZipCode,
      isEstimated: b.pickupLocationEstimated,
    };
  });

  /**
   * Computed: Whether booking has delivery info.
   */
  hasDeliveryInfo = computed(() => {
    const b = this.booking();
    return (
      b !== null &&
      b.deliveryDistanceKm !== null &&
      b.deliveryDistanceKm !== undefined &&
      b.deliveryDistanceKm > 0
    );
  });

  ngOnInit(): void {
    this.loadBooking();

    // Live countdown ticker
    this.tickIntervalId = setInterval(() => this.tick.update((t) => t + 1), 60_000);
    this.destroyRef.onDestroy(() => {
      if (this.tickIntervalId) clearInterval(this.tickIntervalId);
    });
  }

  loadBooking(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.error.set('ID rezervacije nije pronađen');
      return;
    }

    this.isLoading.set(true);
    this.error.set(null);

    // Use getBookingDetails for full pickup location data
    this.bookingService.getBookingDetails(parseInt(id, 10)).subscribe({
      next: (booking) => {
        this.booking.set(booking);
        this.isLoading.set(false);
      },
      error: (err) => {
        this.error.set(err.error?.message || 'Nije moguće učitati rezervaciju');
        this.isLoading.set(false);
      },
    });
  }

  goBack(): void {
    this.router.navigate(['/bookings']);
  }

  getStatusLabel(status: string | undefined): string {
    const labels: Record<string, string> = {
      PENDING: 'Na čekanju',
      PENDING_APPROVAL: 'Na čekanju',
      CONFIRMED: 'Potvrđeno',
      ACTIVE: 'Aktivno',
      IN_TRIP: 'U toku',
      COMPLETED: 'Završeno',
      CANCELLED: 'Otkazano',
      DECLINED: 'Odbijeno',
      CHECK_IN_OPEN: 'Čeka check-in',
      CHECK_IN_HOST_COMPLETE: 'Čeka gosta',
      CHECK_IN_COMPLETE: 'Spremno za preuzimanje',
      CHECKOUT_OPEN: 'Čeka povratak',
      CHECKOUT_GUEST_COMPLETE: 'Čeka domaćina',
      CHECKOUT_HOST_COMPLETE: 'Završava se',
      HOST_SUBMITTED: 'Čeka gosta',
      GUEST_ACKNOWLEDGED: 'Potvrđeno',
      NO_SHOW_HOST: 'Domaćin nije došao',
      NO_SHOW_GUEST: 'Gost nije došao',
      EXPIRED: 'Isteklo',
      EXPIRED_SYSTEM: 'Isteklo',
    };
    return labels[status || ''] || status || 'Nepoznato';
  }

  canCheckIn(): boolean {
    const status = this.booking()?.status;
    return status
      ? ['CONFIRMED', 'CHECK_IN_OPEN', 'HOST_SUBMITTED', 'GUEST_ACKNOWLEDGED'].includes(status)
      : false;
  }

  canReview(): boolean {
    const booking = this.booking();
    return booking?.status === 'COMPLETED' && !booking?.reviewSubmitted;
  }

  // ========== LIVE COUNTDOWN COMPUTEDS ==========

  /** Live countdown text for upcoming/ongoing bookings */
  countdownText = computed((): string | null => {
    void this.tick(); // reactive dependency
    const b = this.booking();
    if (!b) return null;
    const now = new Date();
    const start = new Date(b.startTime);
    const end = new Date(b.endTime);
    const status = b.status;

    // Upcoming (before trip starts)
    if (['PENDING_APPROVAL', 'ACTIVE', 'APPROVED', 'CHECK_IN_OPEN', 'CHECK_IN_HOST_COMPLETE', 'CHECK_IN_COMPLETE'].includes(status) && start.getTime() > now.getTime()) {
      return `Počinje za ${this.formatDuration(start.getTime() - now.getTime())}`;
    }

    // In-trip / ongoing
    if (['IN_TRIP', 'CHECKOUT_OPEN'].includes(status)) {
      const msLeft = end.getTime() - now.getTime();
      if (msLeft <= 0) return 'Vraćanje vozila';
      const minutesLeft = Math.floor(msLeft / 60_000);
      if (minutesLeft <= 60) {
        return `Priprema za vraćanje — ${this.formatDuration(msLeft)}`;
      }
      return `Završava se za ${this.formatDuration(msLeft)}`;
    }

    return null;
  });

  /** Icon for countdown banner */
  countdownIcon = computed((): string => {
    void this.tick();
    const b = this.booking();
    if (!b) return 'schedule';
    if (['IN_TRIP', 'CHECKOUT_OPEN'].includes(b.status)) {
      const msLeft = new Date(b.endTime).getTime() - Date.now();
      if (msLeft > 0 && msLeft <= 3_600_000) return 'alarm';
      return 'timelapse';
    }
    return 'schedule';
  });

  /** Whether checkout prep phase is active (≤ 1h to end) */
  isCheckoutPrep = computed((): boolean => {
    void this.tick();
    const b = this.booking();
    if (!b || !['IN_TRIP', 'CHECKOUT_OPEN'].includes(b.status)) return false;
    const msLeft = new Date(b.endTime).getTime() - Date.now();
    return msLeft > 0 && msLeft <= 3_600_000;
  });

  private formatDuration(ms: number): string {
    const totalMinutes = Math.max(0, Math.floor(ms / 60_000));
    const days = Math.floor(totalMinutes / 1440);
    const hours = Math.floor((totalMinutes % 1440) / 60);
    const minutes = totalMinutes % 60;

    if (days > 0) return `${days} ${days === 1 ? 'dan' : 'dana'}`;
    if (hours > 0) {
      const minPart = minutes > 0 ? ` i ${minutes} min` : '';
      return `${hours} ${hours === 1 ? 'sat' : 'sati'}${minPart}`;
    }
    return `${minutes} min`;
  }
}
