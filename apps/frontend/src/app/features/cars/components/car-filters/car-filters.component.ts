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

type CarFiltersFormValue = {
  minPrice: number;
  maxPrice: number;
  make: string | null;
  model: string | null;
  minYear: number;
  maxYear: number;
  minSeats: number;
  transmission: TransmissionType | null;
  features: Feature[];
  sort: CarSortOption;
};

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
  // REMOVED: triggerReset @Input - parent will call resetFilters() directly via @ViewChild
  // This eliminates the circular reset flow where parent broadcasts reset back to child
  @Output() filtersChanged = new EventEmitter<CarSearchCriteria>();
  @Output() resetTriggered = new EventEmitter<void>();

  filterForm!: FormGroup;

  // Store initial defaults for reactive comparison
  private initialDefaults!: CarFiltersFormValue;
  // Guard against reentrant reset calls
  private isResetting = false;

  // Enums for templates
  readonly TransmissionType = TransmissionType;
  readonly Feature = Feature;
  readonly CarSortOption = CarSortOption;

  // Available makes and models (in production, these should come from backend)
  readonly availableMakes = [
    'Alfa Romeo',
    'Aston Martin',
    'Audi',
    'Bentley',
    'BMW',
    'Bugatti',
    'Cadillac',
    'Chevrolet',
    'Chrysler',
    'Citroën',
    'Cupra',
    'Dacia',
    'Dodge',
    'Ferrari',
    'Fiat',
    'Ford',
    'GMC',
    'Honda',
    'Hummer',
    'Hyundai',
    'Infiniti',
    'Jaguar',
    'Jeep',
    'Kia',
    'Lada',
    'Lamborghini',
    'Land Rover',
    'Lexus',
    'Lincoln',
    'Lotus',
    'Maserati',
    'Mazda',
    'McLaren',
    'Mercedes-Benz',
    'Mini',
    'Mitsubishi',
    'Nissan',
    'Opel',
    'Peugeot',
    'Porsche',
    'Renault',
    'Rolls-Royce',
    'Seat',
    'Škoda',
    'Smart',
    'Subaru',
    'Suzuki',
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
    { value: CarSortOption.RATING_DESC, label: 'Ocena: Najviša' },
    { value: CarSortOption.RATING_ASC, label: 'Ocena: Najniža' },
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
    this.initialDefaults = this.buildDefaultFormValue();

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

      // Seats
      minSeats: [this.initialCriteria?.minSeats ?? this.initialDefaults.minSeats],

      // Transmission
      transmission: [this.initialCriteria?.transmission ?? this.initialDefaults.transmission],

      // Features
      features: [this.initialCriteria?.features ? [...this.initialCriteria.features] : []],

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

  /**
   * Single source of truth for clearing all filters.
   * Called by both main page reset button and dialog reset button.
   * Guard prevents reentrant calls during async operations.
   */
  resetFilters(shouldEmit: boolean = true): void {
    // GUARD: Prevent circular/reentrant reset calls
    if (this.isResetting) {
      return;
    }

    this.isResetting = true;

    this.clearAllFilters();

    // Emit reset event to parent to clear URL and trigger fresh search.
    // This is the single point of emission for a reset action.
    if (shouldEmit) {
      this.resetTriggered.emit();
    }

    this.isResetting = false;
  }

  private buildDefaultFormValue(): CarFiltersFormValue {
    return {
      minPrice: this.minPriceLimit,
      maxPrice: this.maxPriceLimit,
      make: null,
      model: null,
      minYear: this.minYearLimit,
      maxYear: this.maxYearLimit,
      minSeats: this.minSeatsLimit,
      transmission: null,
      features: [], // Always default to a fresh, empty array.
      sort: CarSortOption.RELEVANCE,
    };
  }

  private clearAllFilters(): void {
    // Ensure the entire form resets to defaults, but do not emit changes yet.
    const defaultValues = this.buildDefaultFormValue();

    // Reset the entire form to default values without emitting events.
    this.filterForm.reset(defaultValues, { emitEvent: false });

    // IMPORTANT: Explicitly set 'features' to a NEW empty array.
    // This ensures a fresh reference, critical for breaking stale UI state.
    const featuresControl = this.filterForm.get('features');
    featuresControl?.setValue([], { emitEvent: false });

    this.filterForm.markAsPristine();
    this.filterForm.markAsUntouched();

    // CRITICAL: Force change detection to update activeFiltersCount and UI
    this.cdr.detectChanges();
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

  /**
   * Computes active filter count from FRESH form value, not cached controls.
   * This prevents stale counts after reset operations.
   */
  get activeFiltersCount(): number {
    // Null-safe check for form existence
    if (!this.filterForm) {
      return 0;
    }

    // ALWAYS get fresh value from form, never use cached control references
    const v = this.filterForm.value;
    if (!v) {
      return 0;
    }

    // Count filters that differ from defaults
    let count = 0;
    if (v.minPrice !== this.minPriceLimit) count++;
    if (v.maxPrice !== this.maxPriceLimit) count++;
    if (v.make) count++;
    if (v.model) count++;
    if (v.minYear !== this.minYearLimit) count++;
    if (v.maxYear !== this.maxYearLimit) count++;
    if (v.minSeats !== this.minSeatsLimit) count++;
    if (v.transmission) count++;

    // Check features array length from CURRENT value
    const featuresLength = v.features?.length ?? 0;
    if (featuresLength > 0) {
      count++;
    }

    return count;
  }

  openFiltersDialog(): void {
    const dialogRef = this.dialog.open(CarFiltersDialogComponent, {
      width: '80%',
      maxWidth: '900px',
      maxHeight: '90vh',
      panelClass: 'filters-dialog',
      // Unique id forces Angular Material to treat each open as a new instance
      id: `filters-${Date.now()}`,
      data: {
        // CRITICAL: Pass a plain 'value' object, not the FormGroup instance.
        // This ensures the dialog is completely isolated.
        value: { ...this.filterForm.value },
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
      },
      autoFocus: false,
      restoreFocus: false,
    });

    // Use take(1) to ensure handler only fires once and prevent memory leaks.
    dialogRef
      .afterClosed()
      .pipe(take(1))
      .subscribe((result) => {
        // Do nothing if the dialog was cancelled (e.g., backdrop click or 'X' button).
        if (!result) {
          return;
        }

        if (result.action === 'apply') {
          // The dialog is asking to apply its final state to the parent.
          this.filterForm.patchValue(result.value, { emitEvent: false });
          // IMPORTANT: Ensure features is a fresh array.
          this.filterForm
            .get('features')
            ?.setValue(result.value.features ? [...result.value.features] : [], {
              emitEvent: false,
            });
          this.emitFilters(); // Emit once after all changes are applied.
        } else if (result.action === 'filtersReset') {
          // Filters-only reset
          this.resetFilters(true);
        }

        this.cdr.markForCheck();
      });
  }
}
