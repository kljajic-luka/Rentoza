import { CommonModule } from '@angular/common';
import { Component, inject, Inject } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSliderModule } from '@angular/material/slider';
import { MatChipsModule } from '@angular/material/chips';

import { Feature, TransmissionType } from '@core/models/car.model';
import { CarSortOption } from '@core/models/car-search.model';
import { TranslateEnumPipe } from '@shared/pipes/translate-enum.pipe';

interface CarFiltersDialogData {
  filterForm: FormGroup;
  totalResults: number;
  availableMakes: string[];
  availableFeatures: Feature[];
  sortOptions: { value: CarSortOption; label: string }[];
  minPriceLimit: number;
  maxPriceLimit: number;
  minYearLimit: number;
  maxYearLimit: number;
  minSeatsLimit: number;
  maxSeatsLimit: number;
  TransmissionType: typeof TransmissionType;
  Feature: typeof Feature;
  activeFiltersCount: number;
}

@Component({
  selector: 'app-car-filters-dialog',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSliderModule,
    MatChipsModule,
    TranslateEnumPipe,
  ],
  template: `
    <div class="filters-dialog-container">
      <div class="filters-dialog-header">
        <h2 mat-dialog-title>Filteri</h2>
        <button mat-icon-button class="close-btn" (click)="close()" aria-label="Zatvori filtere">
          <mat-icon>close</mat-icon>
        </button>
      </div>

      <mat-dialog-content [formGroup]="data.filterForm">
        <div class="filters-grid">
          <!-- Sort -->
          <div class="filter-item full-width">
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Sortiraj</mat-label>
              <mat-select formControlName="sort">
                <mat-option *ngFor="let option of data.sortOptions" [value]="option.value">
                  {{ option.label }}
                </mat-option>
              </mat-select>
            </mat-form-field>
          </div>

          <!-- Price Range -->
          <div class="filter-item full-width">
            <label class="filter-label">
              Cena po danu
              <span class="filter-value">
                {{ data.filterForm.get('minPrice')?.value | number : '1.0-0' }} -
                {{ data.filterForm.get('maxPrice')?.value | number : '1.0-0' }} RSD
              </span>
            </label>
            <mat-slider [min]="data.minPriceLimit" [max]="data.maxPriceLimit" [step]="10" discrete>
              <input matSliderStartThumb formControlName="minPrice" />
              <input matSliderEndThumb formControlName="maxPrice" />
            </mat-slider>
          </div>

          <!-- Location -->
          <div class="filter-item">
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Lokacija</mat-label>
              <mat-icon matPrefix>place</mat-icon>
              <input matInput formControlName="location" placeholder="Beograd, Novi Sad..." />
            </mat-form-field>
          </div>

          <!-- Make -->
          <div class="filter-item">
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Marka</mat-label>
              <mat-select formControlName="make">
                <mat-option [value]="null">Sve marke</mat-option>
                <mat-option *ngFor="let make of data.availableMakes" [value]="make">
                  {{ make }}
                </mat-option>
              </mat-select>
            </mat-form-field>
          </div>

          <!-- Model -->
          <div class="filter-item">
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Model</mat-label>
              <input matInput formControlName="model" placeholder="Golf, Passat..." />
            </mat-form-field>
          </div>

          <!-- Transmission -->
          <div class="filter-item">
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Menjač</mat-label>
              <mat-select formControlName="transmission">
                <mat-option [value]="null">Svi tipovi</mat-option>
                <mat-option [value]="data.TransmissionType.AUTOMATIC">Automatski</mat-option>
                <mat-option [value]="data.TransmissionType.MANUAL">Manuelni</mat-option>
              </mat-select>
            </mat-form-field>
          </div>

          <!-- Year Range -->
          <div class="filter-item full-width">
            <label class="filter-label">
              Godište
              <span class="filter-value">
                {{ data.filterForm.get('minYear')?.value }} -
                {{ data.filterForm.get('maxYear')?.value }}
              </span>
            </label>
            <mat-slider [min]="data.minYearLimit" [max]="data.maxYearLimit" [step]="1" discrete>
              <input matSliderStartThumb formControlName="minYear" />
              <input matSliderEndThumb formControlName="maxYear" />
            </mat-slider>
          </div>

          <!-- Seats -->
          <div class="filter-item full-width">
            <label class="filter-label">
              Broj sedišta
              <span class="filter-value">{{ data.filterForm.get('minSeats')?.value }}+</span>
            </label>
            <mat-slider [min]="data.minSeatsLimit" [max]="data.maxSeatsLimit" [step]="1" discrete>
              <input matSliderThumb formControlName="minSeats" />
            </mat-slider>
          </div>

          <!-- Features -->
          <div class="filter-item full-width">
            <label class="filter-label">Dodatna oprema</label>
            <div class="features-chips">
              <mat-chip-option
                *ngFor="let feature of data.availableFeatures"
                [selected]="isFeatureSelected(feature)"
                (click)="toggleFeature(feature)"
              >
                {{ feature | translateEnum }}
              </mat-chip-option>
            </div>
          </div>
        </div>
      </mat-dialog-content>

      <mat-dialog-actions>
        <button
          mat-button
          class="reset-btn"
          (click)="reset()"
          [disabled]="data.activeFiltersCount === 0"
        >
          Obriši sve
        </button>
        <button mat-flat-button color="primary" class="show-results-btn" (click)="apply()">
          Prikaži {{ data.totalResults }} rezultata
        </button>
      </mat-dialog-actions>
    </div>
  `,
  styles: [
    `
      .filters-dialog-container {
        display: flex;
        flex-direction: column;
        min-height: 0;
      }

      .filters-dialog-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 0 24px;
        border-bottom: 1px solid var(--divider-color);

        h2 {
          margin: 0;
          font-size: 1.25rem;
          font-weight: 600;
          color: var(--color-text);
        }

        .close-btn {
          color: var(--color-text-muted);
          margin-right: -8px;

          mat-icon {
            font-size: 24px;
            width: 24px;
            height: 24px;
          }

          &:hover {
            color: var(--color-text);
          }
        }
      }

      mat-dialog-content {
        flex: 1;
        overflow-y: auto;
        padding: 24px;
        min-height: 0;
      }

      .filters-grid {
        display: grid;
        grid-template-columns: repeat(2, 1fr);
        gap: 20px;

        @media (max-width: 768px) {
          grid-template-columns: 1fr;
        }
      }

      .filter-item {
        display: flex;
        flex-direction: column;

        &.full-width {
          grid-column: 1 / -1;
        }

        .filter-label {
          display: flex;
          justify-content: space-between;
          align-items: center;
          font-size: 0.875rem;
          font-weight: 500;
          color: var(--color-text);
          margin-bottom: 12px;

          .filter-value {
            font-size: 0.8125rem;
            font-weight: 600;
            color: var(--primary-color);
          }
        }

        mat-form-field {
          width: 100%;
        }

        mat-slider {
          width: 100%;
        }
      }

      .features-chips {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        max-height: 200px;
        overflow-y: auto;
        padding: 4px;

        mat-chip-option {
          font-size: 0.8125rem;
          min-height: 32px;
          padding: 0 12px;
          border-radius: 16px;
          transition: all 0.2s ease;
          cursor: pointer;

          &:hover {
            transform: translateY(-1px);
            box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
          }
        }

        &::-webkit-scrollbar {
          width: 4px;
        }

        &::-webkit-scrollbar-track {
          background: transparent;
        }

        &::-webkit-scrollbar-thumb {
          background: var(--divider-color);
          border-radius: 2px;
        }

        &::-webkit-scrollbar-thumb:hover {
          background: var(--icon-color);
        }
      }

      mat-dialog-actions {
        display: flex;
        gap: 12px;
        padding: 16px 24px;
        margin: 0;
        border-top: 1px solid var(--divider-color);
        justify-content: flex-end;

        .reset-btn {
          font-size: 0.875rem;
          color: var(--color-text-muted);

          &:hover:not([disabled]) {
            color: var(--primary-color);
          }

          &[disabled] {
            opacity: 0.4;
          }
        }

        .show-results-btn {
          height: 44px;
          padding: 0 32px;
          border-radius: 8px;
          font-weight: 600;
          font-size: 0.9375rem;
        }

        @media (max-width: 768px) {
          flex-direction: column-reverse;

          .reset-btn,
          .show-results-btn {
            width: 100%;
          }
        }
      }

      ::ng-deep {
        .mat-mdc-form-field {
          .mat-mdc-text-field-wrapper {
            padding-bottom: 0;
          }

          .mat-mdc-form-field-subscript-wrapper {
            display: none;
          }
        }

        .mat-mdc-slider {
          width: 100%;
          padding: 0;
          margin: 0;

          .mdc-slider__track {
            height: 4px;
          }

          .mdc-slider__thumb-knob {
            width: 16px;
            height: 16px;
            border-width: 2px;
          }
        }
      }
    `,
  ],
})
export class CarFiltersDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<CarFiltersDialogComponent>);

  constructor(@Inject(MAT_DIALOG_DATA) public data: CarFiltersDialogData) {}

  toggleFeature(feature: Feature): void {
    const currentFeatures = this.data.filterForm.get('features')?.value || [];
    const index = currentFeatures.indexOf(feature);

    const updatedFeatures = [...currentFeatures];

    if (index > -1) {
      updatedFeatures.splice(index, 1);
    } else {
      updatedFeatures.push(feature);
    }

    this.data.filterForm.patchValue({ features: updatedFeatures }, { emitEvent: true });
  }

  isFeatureSelected(feature: Feature): boolean {
    const features = this.data.filterForm.get('features')?.value || [];
    return features.includes(feature);
  }

  apply(): void {
    this.dialogRef.close('apply');
  }

  reset(): void {
    this.dialogRef.close('reset');
  }

  close(): void {
    this.dialogRef.close();
  }
}
