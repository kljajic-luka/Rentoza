import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { filter, take, tap } from 'rxjs';

import { AuthService } from '@core/auth/auth.service';
import { environment } from '@environments/environment';
import { PendingRequestsComponent } from '../../components/pending-requests/pending-requests.component';

@Component({
  selector: 'app-owner-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatSnackBarModule,
    PendingRequestsComponent,
  ],
  templateUrl: './owner-dashboard.component.html',
  styleUrls: ['./owner-dashboard.component.scss'],
})
export class OwnerDashboardComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly baseUrl = `${environment.baseApiUrl}/owner`;

  protected readonly isLoading = signal(false);

  protected readonly stats = signal({
    totalCars: 0,
    totalBookings: 0,
    monthlyEarnings: 0,
    averageRating: 0,
  });

  ngOnInit(): void {
    this.loadDashboardData();
  }

  private loadDashboardData(): void {
    this.isLoading.set(true);

    this.authService.currentUser$
      .pipe(
        filter((user): user is NonNullable<typeof user> => {
          const isValid = user !== null && !!(user.email || user.id);
          return isValid;
        }),
        take(1)
      )
      .subscribe({
        next: (user) => {
          const email = user.email || user.id;
          // Fetch owner statistics from backend
          this.http
            .get<{
              totalCars: number;
              totalBookings: number;
              monthlyEarnings: number;
              averageRating: number;
            }>(`${this.baseUrl}/stats/${email}`)
            .subscribe({
              next: (data) => {
                this.stats.set(data);
                this.isLoading.set(false);
              },
              error: (error) => {
                console.error('Error loading dashboard stats:', error);
                this.snackBar.open('Greška pri učitavanju podataka', 'Zatvori', { duration: 3000 });
                this.isLoading.set(false);
              },
            });
        },
      });
  }
}