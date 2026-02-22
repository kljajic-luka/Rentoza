import {
  ChangeDetectionStrategy,
  Component,
  Input,
  inject,
} from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';

import { Car } from '@core/models/car.model';
import { FavoriteButtonComponent } from '@shared/components/favorite-button/favorite-button.component';
import { TranslateEnumPipe } from '@shared/pipes/translate-enum.pipe';
import { ButtonComponent } from '@shared/components/button/button.component';

/**
 * Standalone car listing card — Turo-standard visual.
 * Accepts a Car object as @Input and renders a fully interactive card.
 * Supports skeleton loading state via [loading]="true".
 */
@Component({
  selector: 'app-car-card',
  standalone: true,
  imports: [
    CommonModule,
    RouterModule,
    DecimalPipe,
    MatIconModule,
    FavoriteButtonComponent,
    TranslateEnumPipe,
    ButtonComponent,
  ],
  templateUrl: './car-card.component.html',
  styleUrls: ['./car-card.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CarCardComponent {
  /** Car data to display — required unless loading=true */
  @Input() car!: Car;

  /** Show animated skeleton placeholder while data is loading */
  @Input() loading = false;

  handleImageError(event: Event): void {
    const img = event.target as HTMLImageElement;
    // Hide broken img element — template shows placeholder instead
    img.style.display = 'none';
  }
}
