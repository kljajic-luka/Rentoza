import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';
import { Observable, switchMap, map } from 'rxjs';

import { Car } from '@core/models/car.model';
import { CarService } from '@core/services/car.service';

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
    FlexLayoutModule
  ],
  templateUrl: './car-list.component.html',
  styleUrls: ['./car-list.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CarListComponent implements OnInit {
  private readonly carService = inject(CarService);
  private readonly route = inject(ActivatedRoute);

  cars$!: Observable<Car[]>;
  location$: Observable<string | null> = this.route.queryParamMap.pipe(
    map(params => params.get('location'))
  );

  ngOnInit(): void {
    this.cars$ = this.route.queryParamMap.pipe(
      switchMap(params => {
        const location = params.get('location');
        if (location) {
          return this.carService.getCarsByLocation(location);
        }
        return this.carService.getCars();
      })
    );
  }

  trackByCarId(_index: number, car: Car): string {
    return car.id;
  }
}
