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

interface EarningsData {
  totalEarnings: number;
  thisMonth: number;
  lastMonth: number;
  averagePerBooking: number;
  carEarnings: Array<{
    carId: string;
    brand: string;
    model: string;
    earnings: number;
    bookingsCount: number;
  }>;
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
    thisMonth: 0,
    lastMonth: 0,
    averagePerBooking: 0,
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
              this.earningsData.set({
                totalEarnings: data.totalEarnings ?? 0,
                thisMonth: data.thisMonth ?? 0,
                lastMonth: data.lastMonth ?? 0,
                averagePerBooking: data.averagePerBooking ?? 0,
                carEarnings: data.carEarnings ?? [],
              });
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

  protected getPercentageChange(): number {
    const data = this.earningsData();
    if (data.lastMonth === 0) return 0;
    return ((data.thisMonth - data.lastMonth) / data.lastMonth) * 100;
  }

  protected isPositiveChange(): boolean {
    return this.getPercentageChange() >= 0;
  }
}
