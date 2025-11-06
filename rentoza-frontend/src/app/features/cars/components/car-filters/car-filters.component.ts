import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  EventEmitter,
  Input,
  OnDestroy,
  OnInit,
  Output,
  inject,
} from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSliderModule } from '@angular/material/slider';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { Subject, take } from 'rxjs';
import { animate, style, transition, trigger } from '@angular/animations';

import { CarSearchCriteria, CarSortOption } from '@core/models/car-search.model';
import { Feature, TransmissionType } from '@core/models/car.model';
import { CarService } from '@core/services/car.service';
import { CarFiltersDialogComponent } from './car-filters-dialog.component';

@Component({
  selector: 'app-car-filters',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSliderModule,
    MatExpansionModule,
    MatChipsModule,
    MatDialogModule,
  ],
  templateUrl: './car-filters.component.html',
  styleUrls: ['./car-filters.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  animations: [
    trigger('fadeInOut', [
      transition(':enter', [
        style({ opacity: 0, transform: 'translateY(-10px)' }),
        animate(
          '0.25s cubic-bezier(0.4, 0, 0.2, 1)',
          style({ opacity: 1, transform: 'translateY(0)' })
        ),
      ]),
    ]),
  ],
})
export class CarFiltersComponent implements OnInit, OnDestroy {
  private readonly fb = inject(FormBuilder);
  private readonly carService = inject(CarService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly dialog = inject(MatDialog);
  private readonly destroy$ = new Subject<void>();

  @Input() totalResults = 0;
  @Input() initialCriteria?: CarSearchCriteria;
  @Input() set triggerReset(value: boolean) {
    if (value) {
      this.resetFilters();
    }
  }
  @Output() filtersChanged = new EventEmitter<CarSearchCriteria>();
  @Output() resetTriggered = new EventEmitter<void>();

  filterForm!: FormGroup;

  // Store initial defaults for reactive comparison
  private initialDefaults: any = {};

  // Enums for templates
  readonly TransmissionType = TransmissionType;
  readonly Feature = Feature;
  readonly CarSortOption = CarSortOption;

  // Available makes and models (in production, these should come from backend)
  readonly availableMakes = [
    'Audi',
    'BMW',
    'Ford',
    'Honda',
    'Hyundai',
    'Mercedes-Benz',
    'Nissan',
    'Opel',
    'Peugeot',
    'Renault',
    'Škoda',
    'Tesla',
    'Toyota',
    'Volkswagen',
    'Volvo',
  ];

  readonly availableFeatures: Feature[] = [
    Feature.AIR_CONDITIONING,
    Feature.BLUETOOTH,
    Feature.NAVIGATION,
    Feature.USB,
    Feature.ANDROID_AUTO,
    Feature.APPLE_CARPLAY,
    Feature.PARKING_SENSORS,
    Feature.REVERSE_CAMERA,
    Feature.CRUISE_CONTROL,
    Feature.HEATED_SEATS,
    Feature.LEATHER_SEATS,
    Feature.SUNROOF,
    Feature.KEYLESS_ENTRY,
  ];

  readonly sortOptions = [
    { value: CarSortOption.RELEVANCE, label: 'Relevantnost' },
    { value: CarSortOption.PRICE_ASC, label: 'Cena: Rastuće' },
    { value: CarSortOption.PRICE_DESC, label: 'Cena: Opadajuće' },
    { value: CarSortOption.YEAR_DESC, label: 'Godina: Najnovije' },
    { value: CarSortOption.YEAR_ASC, label: 'Godina: Najstarije' },
  ];

  // Price range limits
  readonly minPriceLimit = 0;
  maxPriceLimit = 500; // Dynamic, fetched from backend

  // Year range limits
  readonly minYearLimit = 2000;
  readonly maxYearLimit = new Date().getFullYear() + 1;

  // Seats range
  readonly minSeatsLimit = 2;
  readonly maxSeatsLimit = 9;

  ngOnInit(): void {
    // Fetch dynamic max price before initializing form
    this.carService
      .getMaxPrice()
      .pipe(take(1))
      .subscribe((maxPrice) => {
        this.maxPriceLimit = maxPrice;
        this.initializeForm();
        // Note: No setupFilterSubscriptions() - filters apply only on "Prikaži rezultate" button click
        this.cdr.markForCheck();
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private initializeForm(): void {
    // Store initial defaults for later comparison
    this.initialDefaults = {
      minPrice: this.minPriceLimit,
      maxPrice: this.maxPriceLimit,
      make: null,
      model: null,
      minYear: this.minYearLimit,
      maxYear: this.maxYearLimit,
      location: null,
      minSeats: this.minSeatsLimit,
      transmission: null,
      features: [],
      sort: CarSortOption.RELEVANCE,
    };

    this.filterForm = this.fb.group({
      // Price
      minPrice: [this.initialCriteria?.minPrice ?? this.initialDefaults.minPrice],
      maxPrice: [this.initialCriteria?.maxPrice ?? this.initialDefaults.maxPrice],

      // Vehicle details
      make: [this.initialCriteria?.make ?? this.initialDefaults.make],
      model: [this.initialCriteria?.model ?? this.initialDefaults.model],

      // Year
      minYear: [this.initialCriteria?.minYear ?? this.initialDefaults.minYear],
      maxYear: [this.initialCriteria?.maxYear ?? this.initialDefaults.maxYear],

      // Location
      location: [this.initialCriteria?.location ?? this.initialDefaults.location],

      // Seats
      minSeats: [this.initialCriteria?.minSeats ?? this.initialDefaults.minSeats],

      // Transmission
      transmission: [this.initialCriteria?.transmission ?? this.initialDefaults.transmission],

      // Features
      features: [this.initialCriteria?.features ?? this.initialDefaults.features],

      // Sorting
      sort: [this.initialCriteria?.sort ?? this.initialDefaults.sort],
    });
  }

  private emitFilters(): void {
    const formValue = this.filterForm.value;

    // Reactive comparison: only emit params that differ from initial defaults
    const criteria: CarSearchCriteria = {};

    // Price range: only include if not at the full default range
    if (
      formValue.minPrice > this.initialDefaults.minPrice ||
      formValue.maxPrice < this.initialDefaults.maxPrice
    ) {
      criteria.minPrice = formValue.minPrice;
      criteria.maxPrice = formValue.maxPrice;
    }

    // Year range: only include if not at the full default range
    if (
      formValue.minYear > this.initialDefaults.minYear ||
      formValue.maxYear < this.initialDefaults.maxYear
    ) {
      criteria.minYear = formValue.minYear;
      criteria.maxYear = formValue.maxYear;
    }

    // Text fields: omit if empty or null
    if (formValue.make) criteria.make = formValue.make;
    if (formValue.model) criteria.model = formValue.model;
    if (formValue.location) criteria.location = formValue.location;

    // Seats: omit if equals default
    if (formValue.minSeats !== this.initialDefaults.minSeats) {
      criteria.minSeats = formValue.minSeats;
    }

    // Transmission: omit if null
    if (formValue.transmission) {
      criteria.transmission = formValue.transmission;
    }

    // Features: omit if empty array
    if (formValue.features?.length > 0) {
      criteria.features = formValue.features;
    }

    // Sort: omit if default (RELEVANCE)
    if (formValue.sort !== this.initialDefaults.sort) {
      criteria.sort = formValue.sort;
    }

    this.filtersChanged.emit(criteria);
  }

  resetFilters(): void {
    // Reset form to initial default values
    this.filterForm.patchValue(this.initialDefaults, { emitEvent: false });

    // Emit reset event to parent to clear URL and trigger fresh search
    this.resetTriggered.emit();
  }

  formatPriceLabel(value: number): string {
    return `${value} RSD`;
  }

  formatYearLabel(value: number): string {
    return `${value}`;
  }

  formatSeatsLabel(value: number): string {
    return `${value}`;
  }

  get activeFiltersCount(): number {
    // Null-safe check for form existence
    if (!this.filterForm) {
      return 0;
    }

    const formValue = this.filterForm.value;
    if (!formValue) {
      return 0;
    }

    let count = 0;

    if (formValue.minPrice !== this.minPriceLimit) count++;
    if (formValue.maxPrice !== this.maxPriceLimit) count++;
    if (formValue.make) count++;
    if (formValue.model) count++;
    if (formValue.minYear !== this.minYearLimit) count++;
    if (formValue.maxYear !== this.maxYearLimit) count++;
    if (formValue.location) count++;
    if (formValue.minSeats !== this.minSeatsLimit) count++;
    if (formValue.transmission) count++;
    if (formValue.features?.length > 0) count++;

    return count;
  }

  openFiltersDialog(): void {
    // Save current form state to restore on cancel
    const originalFormValue = { ...this.filterForm.value };

    const dialogRef = this.dialog.open(CarFiltersDialogComponent, {
      width: '80%',
      maxWidth: '900px',
      maxHeight: '90vh',
      panelClass: 'filters-dialog',
      data: {
        filterForm: this.filterForm,
        totalResults: this.totalResults,
        availableMakes: this.availableMakes,
        availableFeatures: this.availableFeatures,
        sortOptions: this.sortOptions,
        minPriceLimit: this.minPriceLimit,
        maxPriceLimit: this.maxPriceLimit,
        minYearLimit: this.minYearLimit,
        maxYearLimit: this.maxYearLimit,
        minSeatsLimit: this.minSeatsLimit,
        maxSeatsLimit: this.maxSeatsLimit,
        TransmissionType: this.TransmissionType,
        Feature: this.Feature,
        activeFiltersCount: this.activeFiltersCount,
      },
      autoFocus: false,
      restoreFocus: false,
    });

    dialogRef.afterClosed().subscribe((result) => {
      if (result === 'apply') {
        // Apply filters and update car list
        this.emitFilters();
      } else if (result === 'reset') {
        // Reset filters to defaults
        this.resetFilters();
      } else {
        // Cancel or backdrop click - restore original form values without applying
        this.filterForm.patchValue(originalFormValue, { emitEvent: false });
      }
      this.cdr.markForCheck();
    });
  }
}
