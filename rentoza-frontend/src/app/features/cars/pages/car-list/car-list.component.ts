import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  OnDestroy,
  ViewChild,
} from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { FlexLayoutModule } from '@ngbracket/ngx-layout';
import {
  BehaviorSubject,
  Observable,
  Subject,
  combineLatest,
  debounceTime,
  distinctUntilChanged,
  switchMap,
  takeUntil,
  tap,
  catchError,
  of,
} from 'rxjs';

import { Car, Feature, TransmissionType } from '@core/models/car.model';
import { CarSearchCriteria, PagedResponse } from '@core/models/car-search.model';
import { CarService } from '@core/services/car.service';
import { FavoriteButtonComponent } from '@shared/components/favorite-button/favorite-button.component';
import { CarFiltersComponent } from '../../components/car-filters/car-filters.component';
import { TranslateEnumPipe } from '@shared/pipes/translate-enum.pipe';

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
    MatPaginatorModule,
    MatSnackBarModule,
    FlexLayoutModule,
    FavoriteButtonComponent,
    CarFiltersComponent,
    TranslateEnumPipe,
  ],
  templateUrl: './car-list.component.html',
  styleUrls: ['./car-list.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CarListComponent implements OnInit, OnDestroy {
  private readonly carService = inject(CarService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroy$ = new Subject<void>();

  // Reference to child filter component for direct method calls
  @ViewChild(CarFiltersComponent) filtersComponent!: CarFiltersComponent;

  // Search state
  readonly searchCriteria$ = new BehaviorSubject<CarSearchCriteria>({
    page: 0,
    size: 20,
  });

  // Availability search state
  readonly availabilityParams$ = new BehaviorSubject<{
    location: string;
    startDate: string;
    startTime: string;
    endDate: string;
    endTime: string;
    page: number;
    size: number;
  } | null>(null);

  // Flag to track if we're in availability search mode
  readonly isAvailabilityMode$ = new BehaviorSubject<boolean>(false);

  // Loading state for smooth spinner overlay
  readonly isLoading$ = new BehaviorSubject<boolean>(false);

  // REMOVED: resetForm$ - no longer broadcast reset commands to child
  // Parent will call child.resetFilters() directly via ViewChild reference

  // Search results - conditionally uses availability or standard search
  readonly searchResults$: Observable<PagedResponse<Car>> = combineLatest([
    this.isAvailabilityMode$,
    this.availabilityParams$,
    this.searchCriteria$
  ]).pipe(
    debounceTime(100),
    distinctUntilChanged((prev, curr) => JSON.stringify(prev) === JSON.stringify(curr)),
    tap(() => this.isLoading$.next(true)),
    switchMap(([isAvailability, availParams, criteria]) => {
      if (isAvailability && availParams) {
        // Availability search mode
        return this.carService.searchAvailableCars(
          availParams.location,
          availParams.startDate,
          availParams.startTime,
          availParams.endDate,
          availParams.endTime,
          availParams.page,
          availParams.size
        );
      } else {
        // Standard search mode
        return this.carService.searchCars(criteria);
      }
    }),
    tap((results) => {
      // Update URL with current filters (instant sync)
      if (this.isAvailabilityMode$.value && this.availabilityParams$.value) {
        this.updateUrlParamsForAvailability(this.availabilityParams$.value);
      } else {
        this.updateUrlParams(this.searchCriteria$.value);
      }
      this.isLoading$.next(false);
    }),
    catchError((error) => {
      this.isLoading$.next(false);

      // Determine error message based on HTTP status
      let errorMessage = 'Došlo je do greške. Pokušajte ponovo.';

      if (error.status === 400) {
        errorMessage = 'Neispravan unos. Proverite datume i vreme.';
      } else if (error.status === 500) {
        errorMessage = 'Došlo je do greške na serveru. Pokušajte ponovo.';
      }

      // Show error message
      this.snackBar.open(errorMessage, 'Zatvori', {
        duration: 5000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom',
      });

      // Return empty result set
      return of({
        content: [],
        totalElements: 0,
        totalPages: 0,
        currentPage: 0,
        pageSize: 20,
        hasNext: false,
        hasPrevious: false,
      } as PagedResponse<Car>);
    })
  );

  // Active filter chips
  readonly activeFilterChips$ = new BehaviorSubject<
    Array<{ label: string; value: string; key: keyof CarSearchCriteria }>
  >([]);

  // Availability filter display
  readonly availabilityFilterDisplay$ = new BehaviorSubject<{
    location: string;
    dateTimeRange: string;
  } | null>(null);

  ngOnInit(): void {
    // Initialize filters from URL query params
    this.route.queryParamMap.pipe(takeUntil(this.destroy$)).subscribe((params) => {
      const isAvailabilitySearch = params.get('availabilitySearch') === 'true';

      if (isAvailabilitySearch) {
        // AVAILABILITY SEARCH MODE
        const location = params.get('location') || '';
        const startDate = params.get('startDate') || '';
        const startTime = params.get('startTime') || '09:00';
        const endDate = params.get('endDate') || '';
        const endTime = params.get('endTime') || '18:00';
        const page = params.get('page') ? Number(params.get('page')) : 0;
        const size = params.get('size') ? Number(params.get('size')) : 20;

        // Update availability params
        this.availabilityParams$.next({
          location,
          startDate,
          startTime,
          endDate,
          endTime,
          page,
          size,
        });

        // Enable availability mode
        this.isAvailabilityMode$.next(true);

        // Update availability filter display
        this.updateAvailabilityFilterDisplay(location, startDate, startTime, endDate, endTime);

        // Clear standard filter chips in availability mode
        this.activeFilterChips$.next([]);
      } else {
        // STANDARD SEARCH MODE
        const criteria: CarSearchCriteria = {
          minPrice: params.get('minPrice') ? Number(params.get('minPrice')) : undefined,
          maxPrice: params.get('maxPrice') ? Number(params.get('maxPrice')) : undefined,
          make: params.get('make') || undefined,
          model: params.get('model') || undefined,
          minYear: params.get('minYear') ? Number(params.get('minYear')) : undefined,
          maxYear: params.get('maxYear') ? Number(params.get('maxYear')) : undefined,
          location: params.get('location') || undefined,
          minSeats: params.get('minSeats') ? Number(params.get('minSeats')) : undefined,
          transmission: (params.get('transmission') as TransmissionType) || undefined,
          features: params.get('features')?.split(',').filter(Boolean) as Feature[] | undefined,
          page: params.get('page') ? Number(params.get('page')) : 0,
          size: params.get('size') ? Number(params.get('size')) : 20,
          sort: params.get('sort') || undefined,
        };

        // Disable availability mode
        this.isAvailabilityMode$.next(false);
        this.availabilityParams$.next(null);
        this.availabilityFilterDisplay$.next(null);

        // Update standard search criteria
        this.searchCriteria$.next(criteria);
        this.updateActiveFilterChips(criteria);
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  onFiltersChanged(criteria: CarSearchCriteria): void {
    // Reset to page 0 when filters change
    this.searchCriteria$.next({ ...criteria, page: 0, size: this.searchCriteria$.value.size });
  }

  onPageChange(event: PageEvent): void {
    if (this.isAvailabilityMode$.value && this.availabilityParams$.value) {
      // Availability mode - update availability params
      this.availabilityParams$.next({
        ...this.availabilityParams$.value,
        page: event.pageIndex,
        size: event.pageSize,
      });
    } else {
      // Standard mode - update criteria
      const currentCriteria = this.searchCriteria$.value;
      this.searchCriteria$.next({
        ...currentCriteria,
        page: event.pageIndex,
        size: event.pageSize,
      });
    }

    // Scroll to top on page change
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  removeFilterChip(key: keyof CarSearchCriteria): void {
    const currentCriteria = { ...this.searchCriteria$.value };

    // Special handling for price and year ranges (remove both min/max)
    if (key === 'minPrice') {
      delete currentCriteria.minPrice;
      delete currentCriteria.maxPrice;
    } else if (key === 'minYear') {
      delete currentCriteria.minYear;
      delete currentCriteria.maxYear;
    } else {
      // Remove the specific filter
      delete currentCriteria[key];
    }

    // Reset to page 0
    currentCriteria.page = 0;

    // Update criteria and trigger search
    this.searchCriteria$.next(currentCriteria);

    // Update active filter chips
    this.updateActiveFilterChips(currentCriteria);

    // Immediately sync URL to reflect removed filter
    this.syncUrlToActiveCriteria(currentCriteria);
  }

  clearAllFilters(): void {
    // Reset to completely empty criteria (default state)
    const defaultCriteria: CarSearchCriteria = {
      page: 0,
      size: this.searchCriteria$.value.size,
    };

    this.searchCriteria$.next(defaultCriteria);

    this.updateActiveFilterChips(defaultCriteria);

    // REMOVED: No longer call resetForm$.next(true) - this created circular reset
    // The child component already called resetFilters() which emitted resetTriggered
    // that triggered this clearAllFilters() method. No need to echo back.

    // Clear URL query params
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {},
      replaceUrl: true,
    });
  }

  onResetFilters(): void {
    // Called when reset button is clicked in filter component
    this.clearAllFilters();
  }

  /**
   * NEW: Handler for "Obriši sve filtere" button clicks on the main page.
   * This directly calls the child filter component's resetFilters() method.
   * The child will then emit resetTriggered, which calls onResetFilters() above.
   * This establishes one-way flow: UI click → child reset → parent clears.
   */
  onClearAllFiltersClick(): void {
    if (this.filtersComponent) {
      this.filtersComponent.resetFilters(true);
    }
  }

  private updateActiveFilterChips(criteria: CarSearchCriteria): void {
    const chips: Array<{ label: string; value: string; key: keyof CarSearchCriteria }> = [];

    if (criteria.minPrice !== undefined || criteria.maxPrice !== undefined) {
      const min = criteria.minPrice ?? 0;
      const max = criteria.maxPrice ?? 500;
      chips.push({
        label: 'Cena',
        value: `${min} - ${max} RSD`,
        key: 'minPrice',
      });
    }

    if (criteria.make) {
      chips.push({ label: 'Marka', value: criteria.make, key: 'make' });
    }

    if (criteria.model) {
      chips.push({ label: 'Model', value: criteria.model, key: 'model' });
    }

    if (criteria.minYear !== undefined || criteria.maxYear !== undefined) {
      const min = criteria.minYear ?? 2000;
      const max = criteria.maxYear ?? new Date().getFullYear();
      chips.push({
        label: 'Godina',
        value: `${min} - ${max}`,
        key: 'minYear',
      });
    }

    if (criteria.location) {
      chips.push({ label: 'Lokacija', value: criteria.location, key: 'location' });
    }

    if (criteria.minSeats !== undefined) {
      chips.push({ label: 'Sedišta', value: `${criteria.minSeats}+`, key: 'minSeats' });
    }

    if (criteria.transmission) {
      const transmissionLabel =
        criteria.transmission === TransmissionType.AUTOMATIC ? 'Automatski' : 'Manuelni';
      chips.push({ label: 'Menjač', value: transmissionLabel, key: 'transmission' });
    }

    if (criteria.features && criteria.features.length > 0) {
      chips.push({
        label: 'Oprema',
        value: `${criteria.features.length} opcija`,
        key: 'features',
      });
    }

    this.activeFilterChips$.next(chips);
  }

  /**
   * Updates URL params to match criteria (used during normal search flow)
   */
  private updateUrlParams(criteria: CarSearchCriteria): void {
    const queryParams = this.buildQueryParamsFromCriteria(criteria);

    // Use replaceUrl without merge to completely replace query params
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      replaceUrl: true,
    });
  }

  /**
   * Builds a query params object from a criteria object.
   * This is the single source of truth for URL param generation.
   */
  private buildQueryParamsFromCriteria(criteria: CarSearchCriteria): any {
    const queryParams: any = {};

    // Only add non-default values to URL
    if (criteria.minPrice !== undefined && criteria.minPrice > 0) {
      queryParams.minPrice = criteria.minPrice;
    }
    if (criteria.maxPrice !== undefined && criteria.maxPrice < 500) {
      // Assuming 500 is the max default
      queryParams.maxPrice = criteria.maxPrice;
    }
    if (criteria.make) queryParams.make = criteria.make;
    if (criteria.model) queryParams.model = criteria.model;
    if (criteria.minYear !== undefined && criteria.minYear > 2000) {
      // Assuming 2000 is the min default
      queryParams.minYear = criteria.minYear;
    }
    if (criteria.maxYear !== undefined && criteria.maxYear < new Date().getFullYear() + 1) {
      // Assuming this is max default
      queryParams.maxYear = criteria.maxYear;
    }
    if (criteria.location) queryParams.location = criteria.location;
    if (criteria.minSeats !== undefined) queryParams.minSeats = criteria.minSeats;
    if (criteria.transmission) queryParams.transmission = criteria.transmission;
    if (criteria.features && criteria.features.length > 0) {
      queryParams.features = criteria.features.join(',');
    }
    if (criteria.page && criteria.page > 0) queryParams.page = criteria.page;
    if (criteria.size && criteria.size !== 20) queryParams.size = criteria.size;
    if (criteria.sort) queryParams.sort = criteria.sort;

    return queryParams;
  }

  /**
   * Synchronizes URL query params to exactly match active criteria.
   * Used after filter removal to ensure clean URL state (no stale params).
   */
  private syncUrlToActiveCriteria(criteria: CarSearchCriteria): void {
    const queryParams = this.buildQueryParamsFromCriteria(criteria);

    // Navigate with clean params (replaces all, doesn't merge)
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      replaceUrl: true,
    });
  }

  trackByCarId(_index: number, car: Car): string {
    return car.id;
  }

  /**
   * Update availability filter display with formatted date/time range
   */
  private updateAvailabilityFilterDisplay(
    location: string,
    startDate: string,
    startTime: string,
    endDate: string,
    endTime: string
  ): void {
    // Format dates to dd.MM.yyyy HH:mm
    const formattedStart = this.formatDateTime(startDate, startTime);
    const formattedEnd = this.formatDateTime(endDate, endTime);

    this.availabilityFilterDisplay$.next({
      location: location || 'Sve lokacije',
      dateTimeRange: `${formattedStart} → ${formattedEnd}`,
    });
  }

  /**
   * Format date and time to user-friendly display format (dd.MM.yyyy HH:mm)
   */
  private formatDateTime(date: string, time: string): string {
    if (!date || !time) return '';

    try {
      // Parse YYYY-MM-DD
      const [year, month, day] = date.split('-');
      return `${day}.${month}.${year} ${time}`;
    } catch {
      return `${date} ${time}`;
    }
  }

  /**
   * Update URL params for availability search mode
   */
  private updateUrlParamsForAvailability(params: {
    location: string;
    startDate: string;
    startTime: string;
    endDate: string;
    endTime: string;
    page: number;
    size: number;
  }): void {
    const queryParams: any = {
      availabilitySearch: 'true',
      location: params.location,
      startDate: params.startDate,
      startTime: params.startTime,
      endDate: params.endDate,
      endTime: params.endTime,
    };

    // Only add page/size if non-default
    if (params.page > 0) {
      queryParams.page = params.page;
    }
    if (params.size !== 20) {
      queryParams.size = params.size;
    }

    this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      replaceUrl: true,
    });
  }
}
