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

import { Car, ApprovalStatus } from '@core/models/car.model';
import { CarService } from '@core/services/car.service';
import { AuthService } from '@core/auth/auth.service';
import { environment } from '@environments/environment';
import { EditCarDialogComponent } from '../../dialogs/edit-car-dialog/edit-car-dialog.component';
import { CarAvailabilityDialogComponent } from '../../components/car-availability-dialog/car-availability-dialog.component';
import { EmptyStateComponent } from '@shared/components/empty-state/empty-state.component';
import { LoadingSkeletonComponent } from '@shared/components/loading-skeleton/loading-skeleton.component';

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
    EmptyStateComponent,
    LoadingSkeletonComponent,
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
  protected readonly ApprovalStatus = ApprovalStatus;

  private readonly loggedMissingImageForCarIds = new Set<string>();
  private loggedSample = false;

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
        take(1),
      )
      .subscribe({
        next: (currentUser) => {
          const email = currentUser.email || currentUser.id;
          this.carService.getOwnerCars(email).subscribe({
            next: (cars) => {
              this.cars.set(cars);
              this.debugLogCarImages(cars);
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

  private debugLogCarImages(cars: Car[]): void {
    if (environment.production) {
      return;
    }

    if (!this.loggedSample && cars.length > 0) {
      this.loggedSample = true;
      const sample = cars[0];
      console.info('[MyCars] Sample car image fields (runtime)', {
        carId: sample.id,
        imageUrlPrefix:
          typeof sample.imageUrl === 'string' ? sample.imageUrl.slice(0, 32) : sample.imageUrl,
        imageUrlsCount: Array.isArray(sample.imageUrls) ? sample.imageUrls.length : 0,
        firstImageUrlPrefix:
          Array.isArray(sample.imageUrls) && sample.imageUrls[0]
            ? String(sample.imageUrls[0]).slice(0, 32)
            : null,
      });
    }

    for (const car of cars) {
      const hasImageUrl = typeof car.imageUrl === 'string' && car.imageUrl.trim().length > 0;
      const hasImageUrls = Array.isArray(car.imageUrls) && car.imageUrls.length > 0;

      // This component shows the placeholder when car.imageUrl is falsy.
      if (!hasImageUrl && !this.loggedMissingImageForCarIds.has(car.id)) {
        this.loggedMissingImageForCarIds.add(car.id);
        // Evidence: runtime values used by template.
        // If imageUrls has data URLs but imageUrl is null, mapping/backfill is the issue.
        console.warn('[MyCars] Missing car.imageUrl; rendering placeholder', {
          carId: car.id,
          imageUrl: car.imageUrl,
          imageUrls: car.imageUrls,
          hasImageUrls,
        });
      }
    }
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

    if (newAvailability && car.approvalStatus !== ApprovalStatus.APPROVED) {
      this.snackBar.open(
        'Vozilo mora biti odobreno od strane administratora pre aktivacije.',
        'Zatvori',
        { duration: 4000, panelClass: ['snackbar-warning'] },
      );
      return;
    }

    this.carService.toggleAvailability(car.id, newAvailability).subscribe({
      next: (updatedCar) => {
        // Update local state
        this.cars.update((cars) =>
          cars.map((c) => (c.id === car.id ? { ...c, available: updatedCar.available } : c)),
        );
        this.carService.clearSearchCache(); // Clear cache to ensure fresh results
        const message = updatedCar.available
          ? 'Vozilo je ponovo aktivirano i dostupno u pretrazi.'
          : 'Vozilo je uspešno deaktivirano. Više nije vidljivo u pretrazi.';
        this.snackBar.open(message, 'Zatvori', { duration: 3000 });
      },
      error: (error) => {
        console.error('Error toggling availability:', error);
        const errorMessage =
          error.error?.error || error.error?.message || 'Greška pri promeni statusa';
        this.snackBar.open(errorMessage, 'Zatvori', { duration: 4000 });
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

  /**
   * Handle broken car images by falling back to placeholder SVG.
   * Prevents broken image icons from showing in the UI.
   */
  protected handleImageError(event: Event): void {
    const img = event.target as HTMLImageElement;
    if (img && !img.src.includes('placeholder-car.svg')) {
      img.src = '/images/placeholder-car.svg';
      img.alt = 'Slika nije dostupna';
    }
  }
}
