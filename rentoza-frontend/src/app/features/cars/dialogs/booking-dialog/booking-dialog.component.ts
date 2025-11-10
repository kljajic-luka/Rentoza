import { Component, Inject, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatRadioModule } from '@angular/material/radio';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { Car } from '@core/models/car.model';
import { BookingService } from '@core/services/booking.service';
import { AuthService } from '@core/auth/auth.service';
import { take } from 'rxjs';

export interface BookingDialogData {
  car: Car;
  bookings: any[];
  blockedDates: Date[];
  startDateFilter: (d: Date | null) => boolean;
  endDateFilter: (d: Date | null) => boolean;
}

@Component({
  selector: 'app-booking-dialog',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatRadioModule,
    MatCheckboxModule,
    MatDatepickerModule,
    MatIconModule,
    MatDividerModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
  ],
  templateUrl: './booking-dialog.component.html',
  styleUrls: ['./booking-dialog.component.scss'],
})
export class BookingDialogComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly bookingService = inject(BookingService);
  private readonly authService = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialogRef = inject(MatDialogRef<BookingDialogComponent>);

  protected readonly isSubmitting = signal(false);
  protected readonly totalPrice = signal(0);
  protected readonly basePrice = signal(0);
  protected readonly insuranceCost = signal(0);
  protected readonly refuelCost = signal(0);
  protected readonly rentalDays = signal(0);

  protected readonly startDateMin = new Date();
  protected endDateMin = new Date();

  protected readonly bookingForm = this.fb.nonNullable.group({
    startDate: [null as Date | null, Validators.required],
    endDate: [null as Date | null, Validators.required],
    driverName: ['', [Validators.required, Validators.minLength(2)]],
    driverSurname: ['', [Validators.required, Validators.minLength(2)]],
    driverAge: [
      null as number | null,
      [Validators.required, Validators.min(21), Validators.max(120)],
    ],
    driverPhone: ['', [Validators.required, Validators.pattern(/^[0-9]{8,15}$/)]],
    insuranceType: ['BASIC', Validators.required],
    prepaidRefuel: [false],
  });

  constructor(@Inject(MAT_DIALOG_DATA) public data: BookingDialogData) {}

  ngOnInit(): void {
    // Prefill driver details from current user
    this.authService.currentUser$.pipe(take(1)).subscribe((user) => {
      if (user) {
        this.bookingForm.patchValue({
          driverName: user.firstName || '',
          driverSurname: user.lastName || '',
          driverAge: (user as any).age || null,
          driverPhone: user.phone || '',
        });
      }
    });

    // Recalculate price whenever form values change
    this.bookingForm.valueChanges.subscribe(() => {
      this.calculatePrice();
    });
  }

  protected get startDateFilter() {
    return this.data.startDateFilter;
  }

  protected get endDateFilter() {
    return this.data.endDateFilter;
  }

  protected handleStartDateChange(date: Date | null): void {
    if (!date) return;

    // Update minimum end date to be one day after start date
    const minEnd = new Date(date);
    minEnd.setDate(minEnd.getDate() + 1);
    this.endDateMin = minEnd;

    // Clear end date if it's now invalid
    const endDate = this.bookingForm.value.endDate;
    if (endDate && endDate <= date) {
      this.bookingForm.patchValue({ endDate: null });
    }

    this.calculatePrice();
  }

  protected handleEndDateChange(date: Date | null): void {
    if (!date) return;
    this.calculatePrice();
  }

  private calculatePrice(): void {
    const start = this.bookingForm.value.startDate;
    const end = this.bookingForm.value.endDate;
    const insuranceType = this.bookingForm.value.insuranceType || 'BASIC';
    const prepaidRefuel = this.bookingForm.value.prepaidRefuel || false;

    if (!start || !end) {
      this.resetPriceCalculation();
      return;
    }

    // Calculate days
    const days = Math.ceil((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24));
    this.rentalDays.set(days);

    // Base price
    const base = days * this.data.car.pricePerDay;
    this.basePrice.set(base);

    // Insurance cost
    const insuranceMultiplier = this.getInsuranceMultiplier(insuranceType);
    const insurance = base * (insuranceMultiplier - 1);
    this.insuranceCost.set(insurance);

    // Refuel cost
    let refuel = 0;
    if (prepaidRefuel && this.data.car.fuelConsumption) {
      refuel = this.data.car.fuelConsumption * 6.5 * 10;
    }
    this.refuelCost.set(refuel);

    // Total
    const total = base * insuranceMultiplier + refuel;
    this.totalPrice.set(total);
  }

  private getInsuranceMultiplier(type: string): number {
    switch (type?.toUpperCase()) {
      case 'STANDARD':
        return 1.1;
      case 'PREMIUM':
        return 1.2;
      default:
        return 1.0;
    }
  }

  private resetPriceCalculation(): void {
    this.rentalDays.set(0);
    this.basePrice.set(0);
    this.insuranceCost.set(0);
    this.refuelCost.set(0);
    this.totalPrice.set(0);
  }

  protected submitBooking(): void {
    if (this.bookingForm.invalid) {
      this.snackBar.open('Molimo popunite sva obavezna polja', 'Zatvori', { duration: 3000 });
      return;
    }

    const driverAge = this.bookingForm.value.driverAge;
    if (driverAge && driverAge < 21) {
      this.snackBar.open('Morate imati najmanje 21 godinu da biste iznajmili vozilo', 'Zatvori', {
        duration: 4000,
        panelClass: ['snackbar-error'],
      });
      return;
    }

    this.isSubmitting.set(true);

    const payload = {
      carId: this.data.car.id.toString(),
      startDate: this.bookingForm.value.startDate!.toISOString(),
      endDate: this.bookingForm.value.endDate!.toISOString(),
      insuranceType: this.bookingForm.value.insuranceType || 'BASIC',
      prepaidRefuel: this.bookingForm.value.prepaidRefuel || false,
    };

    this.bookingService.createBooking(payload).subscribe({
      next: () => {
        this.snackBar.open(
          'Vaš zahtev za rezervaciju je uspešno poslat! Domaćin će biti obavešten.',
          'Zatvori',
          {
            duration: 4000,
            panelClass: ['snackbar-success'],
          }
        );
        this.dialogRef.close(true);
      },
      error: (error) => {
        console.error('Booking error:', error);
        const errorMessage =
          error.error?.message || 'Greška pri kreiranju rezervacije. Pokušajte ponovo.';
        this.snackBar.open(errorMessage, 'Zatvori', {
          duration: 5000,
          panelClass: ['snackbar-error'],
        });
        this.isSubmitting.set(false);
      },
    });
  }

  protected cancel(): void {
    this.dialogRef.close(false);
  }
}
