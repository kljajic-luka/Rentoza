import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { take } from 'rxjs';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { Car } from '@core/models/car.model';
import { CarService } from '@core/services/car.service';
import { AuthService } from '@core/auth/auth.service';

@Component({
  selector: 'app-my-cars',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatMenuModule,
    MatSnackBarModule,
    MatDividerModule
  ],
  templateUrl: './my-cars.component.html',
  styleUrls: ['./my-cars.component.scss']
})
export class MyCarsComponent implements OnInit {
  private readonly snackBar = inject(MatSnackBar);
  private readonly carService = inject(CarService);
  private readonly authService = inject(AuthService);

  protected readonly isLoading = signal(false);
  protected readonly cars = signal<Car[]>([]);

  ngOnInit(): void {
    this.loadMyCars();
  }

  private loadMyCars(): void {
    this.isLoading.set(true);

    this.authService.currentUser$.pipe(
      take(1) // Take only the current value
    ).subscribe({
      next: (currentUser) => {
        if (!currentUser?.email) {
          this.snackBar.open('Greška: Korisnik nije prijavljen', 'Zatvori', { duration: 3000 });
          this.isLoading.set(false);
          return;
        }

        this.carService.getOwnerCars(currentUser.email).subscribe({
          next: (cars) => {
            this.cars.set(cars);
            this.isLoading.set(false);
          },
          error: (error) => {
            console.error('Error loading owner cars:', error);
            this.snackBar.open('Greška pri učitavanju vozila', 'Zatvori', { duration: 3000 });
            this.isLoading.set(false);
          }
        });
      },
      error: () => {
        this.snackBar.open('Greška: Korisnik nije prijavljen', 'Zatvori', { duration: 3000 });
        this.isLoading.set(false);
      }
    });
  }

  protected editCar(car: Car): void {
    // TODO: Navigate to edit page
    this.snackBar.open(`Uređivanje: ${car.make} ${car.model}`, 'Zatvori', { duration: 2000 });
  }

  protected toggleAvailability(car: Car): void {
    const newAvailability = !car.available;

    this.carService.toggleAvailability(car.id, newAvailability).subscribe({
      next: (updatedCar) => {
        // Update local state
        this.cars.update(cars =>
          cars.map(c => c.id === car.id ? { ...c, available: updatedCar.available } : c)
        );
        const status = updatedCar.available ? 'aktivirano' : 'deaktivirano';
        this.snackBar.open(`Vozilo ${status}`, 'Zatvori', { duration: 2000 });
      },
      error: (error) => {
        console.error('Error toggling availability:', error);
        this.snackBar.open('Greška pri promeni statusa', 'Zatvori', { duration: 3000 });
      }
    });
  }

  protected deleteCar(car: Car): void {
    if (!confirm(`Da li ste sigurni da želite da obrišete ${car.make} ${car.model}?`)) {
      return;
    }

    this.carService.deleteCar(car.id).subscribe({
      next: () => {
        this.cars.update(cars => cars.filter(c => c.id !== car.id));
        this.snackBar.open('Vozilo obrisano', 'Zatvori', { duration: 2000 });
      },
      error: (error) => {
        console.error('Error deleting car:', error);
        this.snackBar.open('Greška pri brisanju vozila', 'Zatvori', { duration: 3000 });
      }
    });
  }

  protected previewCar(car: Car): void {
    // Will navigate to public car detail page
    window.open(`/cars/${car.id}`, '_blank');
  }

  protected getStatusLabel(car: Car): string {
    return car.available ? 'Aktivno' : 'Nedostupno';
  }

  protected getStatusClass(car: Car): string {
    return car.available ? 'status-active' : 'status-inactive';
  }
}
