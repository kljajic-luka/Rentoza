import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatDividerModule } from '@angular/material/divider';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { filter, take } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import { environment } from '@environments/environment';
import { BookingPayoutStatus, OwnerPayoutsResponse } from '@core/models/payout.model';
import {
  getPayoutStatusLabel,
  getPayoutStatusIcon,
  getPayoutStatusColor,
} from '@core/payment/payment-status.mapper';

interface BookingDetail {
  bookingId: number;
  startTime: string; // ISO-8601 datetime
  endTime: string; // ISO-8601 datetime
  totalPrice: number;
  status: string;
}

interface CarEarning {
  carId: number;
  carBrand: string;
  carModel: string;
  earnings: number;
  bookingCount: number;
  bookingDetails: BookingDetail[];
}

interface EarningsData {
  totalEarnings: number;
  monthlyEarnings: number;
  yearlyEarnings: number;
  totalBookings: number;
  carEarnings: CarEarning[];
}

@Component({
  selector: 'app-earnings',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatCardModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatFormFieldModule,
    MatSnackBarModule,
    MatDividerModule,
  ],
  templateUrl: './earnings.component.html',
  styleUrls: ['./earnings.component.scss'],
})
export class EarningsComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly baseUrl = `${environment.baseApiUrl}/owner`;

  protected readonly isLoading = signal(false);
  protected readonly earningsData = signal<EarningsData>({
    totalEarnings: 0,
    monthlyEarnings: 0,
    yearlyEarnings: 0,
    totalBookings: 0,
    carEarnings: [],
  });

  // Payout status dashboard
  protected readonly isLoadingPayouts = signal(false);
  protected readonly payouts = signal<BookingPayoutStatus[]>([]);

  protected readonly periodControl = new FormControl('all');

  ngOnInit(): void {
    this.loadEarnings();
    this.loadPayoutStatuses();
  }

  // Payout status mapper helpers exposed to template
  protected getPayoutLabel(status: string): string {
    return getPayoutStatusLabel(status as BookingPayoutStatus['payoutStatus']);
  }

  protected getPayoutIcon(status: string): string {
    return getPayoutStatusIcon(status as BookingPayoutStatus['payoutStatus']);
  }

  protected getPayoutColor(status: string): string {
    return getPayoutStatusColor(status as BookingPayoutStatus['payoutStatus']);
  }

  private loadEarnings(): void {
    this.isLoading.set(true);

    this.authService.currentUser$
      .pipe(
        filter((user): user is NonNullable<typeof user> => !!user && !!(user.email || user.id)),
        take(1),
      )
      .subscribe({
        next: (user) => {
          const email = user.email || user.id;
          this.http.get<EarningsData>(`${this.baseUrl}/earnings/${email}`).subscribe({
            next: (data) => {
              this.earningsData.set(data);
              this.isLoading.set(false);
            },
            error: (error) => {
              console.error('Error loading earnings data:', error);
              this.snackBar.open('Greska pri ucitavanju zarade', 'Zatvori', { duration: 3000 });
              this.isLoading.set(false);
            },
          });
        },
        error: () => {
          this.snackBar.open('Nije moguce pronaci korisnika', 'Zatvori', { duration: 3000 });
          this.isLoading.set(false);
        },
      });
  }

  /**
   * Load payout statuses from GET /api/owner/payouts.
   */
  private loadPayoutStatuses(): void {
    this.isLoadingPayouts.set(true);

    this.authService.currentUser$
      .pipe(
        filter((user): user is NonNullable<typeof user> => !!user && !!(user.email || user.id)),
        take(1),
      )
      .subscribe({
        next: () => {
          this.http.get<OwnerPayoutsResponse>(`${this.baseUrl}/payouts`).subscribe({
            next: (data) => {
              this.payouts.set(data.payouts ?? []);
              this.isLoadingPayouts.set(false);
            },
            error: (error) => {
              console.error('Error loading payout statuses:', error);
              this.snackBar.open('Greska pri ucitavanju statusa isplata', 'Zatvori', {
                duration: 3000,
              });
              this.isLoadingPayouts.set(false);
            },
          });
        },
        error: () => {
          this.isLoadingPayouts.set(false);
        },
      });
  }
}
