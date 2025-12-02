import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { filter, take } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import { environment } from '@environments/environment';

interface BookingDetail {
  bookingId: number;
  startTime: string;  // ISO-8601 datetime
  endTime: string;    // ISO-8601 datetime
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

  protected readonly periodControl = new FormControl('all');

  ngOnInit(): void {
    this.loadEarnings();
  }

  private loadEarnings(): void {
    this.isLoading.set(true);

    this.authService.currentUser$
      .pipe(
        filter((user): user is NonNullable<typeof user> => !!user && !!(user.email || user.id)),
        take(1)
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
              this.snackBar.open('Greška pri učitavanju zarade', 'Zatvori', { duration: 3000 });
              this.isLoading.set(false);
            },
          });
        },
        error: () => {
          this.snackBar.open('Nije moguće pronaći korisnika', 'Zatvori', { duration: 3000 });
          this.isLoading.set(false);
        },
      });
  }
}
