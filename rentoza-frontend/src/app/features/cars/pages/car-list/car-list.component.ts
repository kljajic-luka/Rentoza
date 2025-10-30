import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';
import { Observable, switchMap, map, combineLatest } from 'rxjs';

import { Car } from '@core/models/car.model';
import { CarService } from '@core/services/car.service';
import { getDistanceBetweenCities, formatDistance } from '@core/utils/distance.util';
import { FavoriteButtonComponent } from '@shared/components/favorite-button/favorite-button.component';

interface CarWithDistance extends Car {
  distance?: string;
}

@Component({
  selector: 'app-car-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    MatCardModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    MatIconModule,
    MatChipsModule,
    FlexLayoutModule,
    FavoriteButtonComponent,
  ],
  templateUrl: './car-list.component.html',
  styleUrls: ['./car-list.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CarListComponent implements OnInit {
  private readonly carService = inject(CarService);
  private readonly route = inject(ActivatedRoute);

  cars$!: Observable<CarWithDistance[]>;
  location$: Observable<string | null> = this.route.queryParamMap.pipe(
    map((params) => params.get('location'))
  );
  searchRadius = 20; // km

  ngOnInit(): void {
    this.cars$ = this.route.queryParamMap.pipe(
      switchMap((params) => {
        const location = params.get('location');
        const carsObservable = location
          ? this.carService.getCarsByLocation(location, this.searchRadius)
          : this.carService.getCars();

        return combineLatest([carsObservable, this.location$]).pipe(
          map(([cars, searchLocation]) => {
            if (!searchLocation) {
              return cars;
            }

            // Add distance information to each car
            return cars
              .map((car) => {
                const distanceKm = getDistanceBetweenCities(car.location, searchLocation);
                return {
                  ...car,
                  distance: distanceKm !== null ? formatDistance(distanceKm) : undefined,
                };
              })
              .sort((a, b) => {
                // Sort by distance (closest first)
                const distA =
                  getDistanceBetweenCities(a.location, searchLocation || '') || Infinity;
                const distB =
                  getDistanceBetweenCities(b.location, searchLocation || '') || Infinity;
                return distA - distB;
              });
          })
        );
      })
    );
  }

  trackByCarId(_index: number, car: Car): string {
    return car.id;
  }
}
