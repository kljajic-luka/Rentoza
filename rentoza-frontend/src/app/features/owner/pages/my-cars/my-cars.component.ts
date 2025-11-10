import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { filter, take, tap } from 'rxjs';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';

import { Car } from '@core/models/car.model';
import { CarService } from '@core/services/car.service';
import { AuthService } from '@core/auth/auth.service';
import { EditCarDialogComponent } from '../../dialogs/edit-car-dialog/edit-car-dialog.component';
import { CarAvailabilityDialogComponent } from '../../components/car-availability-dialog/car-availability-dialog.component';

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
    MatDividerModule,
    MatDialogModule,
  ],
  templateUrl: './my-cars.component.html',
  styleUrls: ['./my-cars.component.scss'],
})
export class MyCarsComponent implements OnInit {
  private readonly snackBar = inject(MatSnackBar);
  private readonly carService = inject(CarService);
  private readonly authService = inject(AuthService);
  private readonly dialog = inject(MatDialog);

  protected readonly isLoading = signal(false);
  protected readonly cars = signal<Car[]>([]);

  ngOnInit(): void {
    this.loadMyCars();
  }

  private loadMyCars(): void {
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
        next: (currentUser) => {
          const email = currentUser.email || currentUser.id;
          this.carService.getOwnerCars(email).subscribe({
            next: (cars) => {
              this.cars.set(cars);
              this.isLoading.set(false);
            },
            error: (error) => {
              console.error('Error loading owner cars:', error);
              this.snackBar.open('Greška pri učitavanju vozila', 'Zatvori', { duration: 3000 });
              this.isLoading.set(false);
            },
          });
        },
      });
  }

  protected editCar(car: Car): void {
    const dialogRef = this.dialog.open(EditCarDialogComponent, {
      width: '800px',
      maxHeight: '90vh',
      data: { car },
      disableClose: false,
    });

    dialogRef.afterClosed().subscribe((updatedCar: Car | undefined) => {
      if (updatedCar) {
        // Update the car in local state
        this.cars.update((cars) => cars.map((c) => (c.id === updatedCar.id ? updatedCar : c)));
      }
    });
  }

  protected toggleAvailability(car: Car): void {
    const newAvailability = !car.available;

    this.carService.toggleAvailability(car.id, newAvailability).subscribe({
      next: (updatedCar) => {
        // Update local state
        this.cars.update((cars) =>
          cars.map((c) => (c.id === car.id ? { ...c, available: updatedCar.available } : c))
        );
        this.carService.clearSearchCache(); // Clear cache to ensure fresh results
        const message = updatedCar.available
          ? 'Vozilo je ponovo aktivirano i dostupno u pretrazi.'
          : 'Vozilo je uspešno deaktivirano. Više nije vidljivo u pretrazi.';
        this.snackBar.open(message, 'Zatvori', { duration: 3000 });
      },
      error: (error) => {
        console.error('Error toggling availability:', error);
        this.snackBar.open('Greška pri promeni statusa', 'Zatvori', { duration: 3000 });
      },
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

  /**
   * Open the availability calendar dialog for managing blocked dates
   */
  protected openCalendarDialog(car: Car): void {
    this.dialog.open(CarAvailabilityDialogComponent, {
      width: '700px',
      maxWidth: '90vw',
      maxHeight: '90vh',
      data: { car },
      disableClose: false,
    });
  }
}
