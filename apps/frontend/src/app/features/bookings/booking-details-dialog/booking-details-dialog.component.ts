import { Component, Inject, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  MAT_DIALOG_DATA,
  MatDialogModule,
  MatDialogRef,
  MatDialog,
} from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
import { BookingService } from '@core/services/booking.service';
import { BookingDetails, PickupLocationData } from '@core/models/booking-details.model';
import { CarRules } from '@app/core/models/car-rules.model';
import {
  CancellationPreviewDialogComponent,
  CancellationPreviewDialogData,
  CancellationPreviewDialogResult,
} from '@shared/components/cancellation-preview-dialog/cancellation-preview-dialog.component';
import { ReadOnlyPickupLocationComponent } from '../components/readonly-pickup-location';
import { AgreementSummary } from '@core/models/booking.model';
import {
  canProceedToCheckInFromSummary,
  shouldShowAgreementPrimaryActionFromSummary,
} from '@core/utils/agreement-summary.utils';
import {
  formatDateSerbiaValue,
  formatDateTimeSerbiaValue,
  getRoundedTripDaysSerbia,
} from '@core/utils/serbia-time.util';

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
    ReadOnlyPickupLocationComponent,
  ],
  templateUrl: './booking-details-dialog.component.html',
  styleUrls: ['./booking-details-dialog.component.scss'],
})
export class BookingDetailsDialogComponent implements OnInit {
  private readonly bookingService = inject(BookingService);
  private readonly dialogRef = inject(MatDialogRef<BookingDetailsDialogComponent>);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);

  booking = signal<BookingDetails | null>(null);
  isLoading = signal(true);
  error = signal<string | null>(null);

  /**
   * Computed: Whether the booking can be cancelled.
   * Only PENDING_APPROVAL and ACTIVE bookings are cancellable by guest.
   */
  protected readonly canCancel = computed(() => {
    const b = this.booking();
    if (!b) return false;
    return b.status === 'PENDING_APPROVAL' || b.status === 'ACTIVE';
  });

  /**
   * Computed: Pickup location data for display component.
   * Returns null if no pickup coordinates available.
   */
  protected readonly pickupLocationData = computed<PickupLocationData | null>(() => {
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
   * Computed: Whether booking has delivery info to display.
   */
  protected readonly hasDeliveryInfo = computed(() => {
    const b = this.booking();
    return (
      b !== null &&
      b.deliveryDistanceKm !== null &&
      b.deliveryDistanceKm !== undefined &&
      b.deliveryDistanceKm > 0
    );
  });

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

  protected getAgreementSummary(): AgreementSummary | null {
    return this.booking()?.agreementSummary ?? null;
  }

  protected shouldShowAgreementPrimaryAction(): boolean {
    return shouldShowAgreementPrimaryActionFromSummary(this.getAgreementSummary());
  }

  protected canOpenCheckIn(): boolean {
    const booking = this.booking();
    return !!booking
      && booking.status === 'CHECK_IN_HOST_COMPLETE'
      && canProceedToCheckInFromSummary(booking.agreementSummary);
  }

  protected getAgreementBannerText(): string {
    const summary = this.getAgreementSummary();
    if (!summary || summary.legacyBooking) {
      return '';
    }

    switch (summary.workflowStatus) {
      case 'AGREEMENT_PENDING_OWNER':
        return 'Domaćin još nije prihvatio ugovor o iznajmljivanju.';
      case 'AGREEMENT_PENDING_RENTER':
      case 'AGREEMENT_PENDING_BOTH':
        return summary.currentActorNeedsAcceptance
          ? 'Prihvatite ugovor o iznajmljivanju pre check-in procesa.'
          : 'Čekamo da druga strana prihvati ugovor o iznajmljivanju.';
      case 'AGREEMENT_EXPIRED_OWNER_BREACH':
        return 'Ugovor je istekao jer domaćin nije prihvatio uslove na vreme.';
      case 'AGREEMENT_EXPIRED_RENTER_BREACH':
        return 'Ugovor je istekao jer nije prihvaćen na vreme sa vaše strane.';
      case 'AGREEMENT_EXPIRED_BOTH_PARTIES':
        return 'Ugovor je istekao jer nije prihvaćen na vreme od obe strane.';
      default:
        return 'Ugovor o iznajmljivanju je spreman za pregled.';
    }
  }

  protected getAgreementActionLabel(): string {
    return this.getAgreementSummary()?.currentActorNeedsAcceptance
      ? 'Pregledaj i prihvati ugovor'
      : 'Status ugovora';
  }

  protected openAgreement(): void {
    this.dialogRef.close();
    void this.router.navigate(['/bookings', this.data.bookingId], {
      queryParams: { agreementRequired: '1' },
    });
  }

  protected openCheckIn(): void {
    this.dialogRef.close();
    void this.router.navigate(['/bookings', this.data.bookingId, 'check-in']);
  }

  openFullBookingPage(): void {
    this.dialogRef.close();
    void this.router.navigate(['/bookings', this.data.bookingId]);
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

  /**
   * Opens the cancellation preview dialog for this booking.
   * Closes the booking details dialog on successful cancellation.
   */
  openCancellationDialog(): void {
    const b = this.booking();
    if (!b) return;

    const dialogData: CancellationPreviewDialogData = {
      bookingId: this.data.bookingId,
      userRole: 'GUEST',
      carInfo: `${b.brand} ${b.model} ${b.year}`,
      tripDates: `${this.formatDate(b.startTime)} - ${this.formatDate(b.endTime)}`,
    };

    const dialogRef = this.dialog.open(CancellationPreviewDialogComponent, {
      width: '500px',
      maxWidth: '95vw',
      disableClose: true,
      data: dialogData,
    });

    dialogRef.afterClosed().subscribe((result: CancellationPreviewDialogResult | undefined) => {
      if (result?.confirmed) {
        // Close the booking details dialog with cancellation result
        this.dialogRef.close({ cancelled: true });
      }
    });
  }

  getDuration(start: string, end: string): number {
    return getRoundedTripDaysSerbia(start, end);
  }

  formatDate(date: string): string {
    return formatDateSerbiaValue(date, {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
    });
  }

  formatDateTime(date: string): string {
    return formatDateTimeSerbiaValue(date);
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
      case 'CANCELLATION_PENDING_SETTLEMENT':
        return 'Otkazano, poravnanje u toku';
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
      case 'CANCELLATION_PENDING_SETTLEMENT':
        return 'warn';
      case 'COMPLETED':
        return 'accent';
      default:
        return '';
    }
  }
}