import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { FormControl, ReactiveFormsModule } from '@angular/forms';

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
    MatFormFieldModule
  ],
  templateUrl: './earnings.component.html',
  styleUrls: ['./earnings.component.scss']
})
export class EarningsComponent implements OnInit {
  protected readonly isLoading = signal(false);
  protected readonly earningsData = signal<EarningsData>({
    totalEarnings: 0,
    thisMonth: 0,
    lastMonth: 0,
    averagePerBooking: 0,
    carEarnings: []
  });

  protected readonly periodControl = new FormControl('all');

  ngOnInit(): void {
    this.loadEarnings();
  }

  private loadEarnings(): void {
    this.isLoading.set(true);

    // TODO: Fetch from backend GET /api/owner/earnings
    setTimeout(() => {
      // Mock data for now
      this.earningsData.set({
        totalEarnings: 125000,
        thisMonth: 35000,
        lastMonth: 42000,
        averagePerBooking: 8500,
        carEarnings: [
          {
            carId: '1',
            brand: 'Toyota',
            model: 'Corolla',
            earnings: 45000,
            bookingsCount: 5
          },
          {
            carId: '2',
            brand: 'Volkswagen',
            model: 'Golf',
            earnings: 38000,
            bookingsCount: 4
          },
          {
            carId: '3',
            brand: 'BMW',
            model: '320d',
            earnings: 42000,
            bookingsCount: 3
          }
        ]
      });
      this.isLoading.set(false);
    }, 500);
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
