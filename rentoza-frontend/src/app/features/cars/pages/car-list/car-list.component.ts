import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';
import { Observable } from 'rxjs';

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
export class CarListComponent {
  private readonly carService = inject(CarService);

  readonly cars$: Observable<Car[]> = this.carService.getCars();

  trackByCarId(_index: number, car: Car): string {
    return car.id;
  }
}
