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
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDatepickerModule, MatDatepicker, MatDatepickerInputEvent } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
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
  map,
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
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    MatIconModule,
    MatChipsModule,
    MatPaginatorModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatDatepickerModule,
    MatNativeDateModule,
    MatAutocompleteModule,
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

  readonly today = new Date();
  searchLocation = '';
  searchStartDate: Date | null = null;
  searchStartTime = '';
  searchEndDate: Date | null = null;
  searchEndTime = '';
  locationError = '';
  startDateError = '';
  endDateError = '';
  startTimeError = '';
  endTimeError = '';
  dateRangeError = '';
  readonly cities = [
    'Beograd',
    'Novi Sad',
    'Niš',
    'Kragujevac',
    'Subotica',
    'Zrenjanin',
    'Pančevo',
    'Čačak',
    'Kraljevo',
    'Novi Pazar',
    'Leskovac',
    'Šabac',
    'Sombor',
    'Užice',
    'Smederevo',
    'Valjevo',
    'Vranje',
    'Loznica',
    'Požarevac',
    'Pirot',
    'Kruševac',
    'Prokuplje',
    'Jagodina',
    'Bor',
    'Kikinda',
    'Vrbas',
    'Zaječar',
    'Sremska Mitrovica',
    'Vršac',
    'Paraćin',
    'Negotin',
    'Ćuprija',
    'Priboj',
    'Aranđelovac',
    'Gornji Milanovac'
  ];

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
        return this.carService
          .searchAvailableCars(
            availParams.location,
            availParams.startDate,
            availParams.startTime,
            availParams.endDate,
            availParams.endTime,
            availParams.page,
            availParams.size
          )
          .pipe(map((results) => this.applyFilters(results, criteria, true)));
      } else {
        // Standard search mode (client-side filtering to keep consistency with availability path)
        return this.carService.searchCars(criteria).pipe(map((results) => this.applyFilters(results, criteria, false)));
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

  readonly timeOptions = [
    '06:00',
    '07:00',
    '08:00',
    '09:00',
    '10:00',
    '11:00',
    '12:00',
    '13:00',
    '14:00',
    '15:00',
    '16:00',
    '17:00',
    '18:00',
    '19:00',
    '20:00',
    '21:00',
    '22:00',
  ];

  get filteredCities(): string[] {
    const query = this.toCanonical(this.searchLocation.trim());

    if (!query) {
      return this.cities;
    }

    return this.cities.filter((city) => this.toCanonical(city).includes(query));
  }

  get datesSelected(): boolean {
    return !!(this.searchStartDate && this.searchEndDate);
  }

  get hasActiveState(): boolean {
    const criteria = this.searchCriteria$.value;
    const hasFilters = !this.isCriteriaDefault(criteria) || (this.activeFilterChips$.value?.length ?? 0) > 0;
    const hasSearch =
      !!this.searchLocation.trim() ||
      !!this.searchStartDate ||
      !!this.searchEndDate ||
      !!this.searchStartTime ||
      !!this.searchEndTime ||
      this.isAvailabilityMode$.value ||
      !!this.availabilityParams$.value;
    return hasFilters || hasSearch;
  }

  private primaryValue(...values: Array<string | undefined | null>): string | undefined {
    for (const v of values) {
      if (v !== undefined && v !== null) {
        const trimmed = String(v).trim();
        if (trimmed.length > 0) {
          return trimmed;
        }
      }
    }
    return undefined;
  }

  private toCanonical(value: string): string {
    return value
      .replace(/đ/gi, 'd')
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '');
  }

  private matchesAllTokens(candidate?: string, filter?: string): boolean {
    const target = this.toCanonical(candidate ?? '');
    const tokens = (filter ?? '')
      .split(/\s+/)
      .map((token) => this.toCanonical(token))
      .filter((token) => token.length > 0);

    if (tokens.length === 0) {
      return true;
    }

    return tokens.every((token) => target.includes(token));
  }

  ngOnInit(): void {
    // Initialize filters from URL query params
    this.route.queryParamMap.pipe(takeUntil(this.destroy$)).subscribe((params) => {
      const isAvailabilitySearch = params.get('availabilitySearch') === 'true';

      const parsedFilters: CarSearchCriteria = {
        minPrice: params.get('minPrice') ? Number(params.get('minPrice')) : undefined,
        maxPrice: params.get('maxPrice') ? Number(params.get('maxPrice')) : undefined,
        make: params.get('make') || undefined,
        model: params.get('model') || undefined,
        minYear: params.get('minYear') ? Number(params.get('minYear')) : undefined,
        maxYear: params.get('maxYear') ? Number(params.get('maxYear')) : undefined,
        minSeats: params.get('minSeats') ? Number(params.get('minSeats')) : undefined,
        transmission: (params.get('transmission') as TransmissionType) || undefined,
        features: params.get('features')?.split(',').filter(Boolean) as Feature[] | undefined,
        page: params.get('page') ? Number(params.get('page')) : 0,
        size: params.get('size') ? Number(params.get('size')) : 20,
        sort: params.get('sort') || undefined,
      };

      if (isAvailabilitySearch) {
        // AVAILABILITY SEARCH MODE
        // Home component sends startTime/endTime as ISO-8601 strings (e.g., "2025-12-02T09:00:00")
        const location = params.get('location') || '';
        const startTimeISO = params.get('startTime') || '';
        const endTimeISO = params.get('endTime') || '';
        const page = params.get('page') ? Number(params.get('page')) : 0;
        const size = params.get('size') ? Number(params.get('size')) : 20;

        // Parse ISO strings to extract date and time for UI display
        let searchStartDate: Date | null = null;
        let searchStartTime = '';
        let searchEndDate: Date | null = null;
        let searchEndTime = '';

        if (startTimeISO && startTimeISO.includes('T')) {
          const startDateObj = new Date(startTimeISO);
          if (!isNaN(startDateObj.getTime())) {
            searchStartDate = startDateObj;
            const [datePart, timePart] = startTimeISO.split('T');
            searchStartTime = timePart.substring(0, 5); // Extract HH:mm
          }
        }

        if (endTimeISO && endTimeISO.includes('T')) {
          const endDateObj = new Date(endTimeISO);
          if (!isNaN(endDateObj.getTime())) {
            searchEndDate = endDateObj;
            const [datePart, timePart] = endTimeISO.split('T');
            searchEndTime = timePart.substring(0, 5); // Extract HH:mm
          }
        }

        // Update availability params (pass ISO strings directly to service)
        this.availabilityParams$.next({
          location,
          startDate: '', // Not used, kept for compatibility
          startTime: startTimeISO, // ISO string: 2025-12-02T09:00:00
          endDate: '', // Not used, kept for compatibility
          endTime: endTimeISO, // ISO string: 2025-12-02T18:00:00
          page,
          size,
        });

        this.searchLocation = location;
        this.searchStartDate = searchStartDate;
        this.searchStartTime = searchStartTime;
        this.searchEndDate = searchEndDate;
        this.searchEndTime = searchEndTime;

        // Enable availability mode
        this.isAvailabilityMode$.next(true);

        // Update availability filter display (use extracted date/time for display)
        const displayStartDate = searchStartDate ? this.formatDate(searchStartDate) : '';
        const displayEndDate = searchEndDate ? this.formatDate(searchEndDate) : '';
        this.updateAvailabilityFilterDisplay(location, displayStartDate, searchStartTime, displayEndDate, searchEndTime);

        // Apply filter criteria parsed from URL alongside availability
        this.searchCriteria$.next(parsedFilters);
        this.updateActiveFilterChips(parsedFilters);
      } else {
        // STANDARD SEARCH MODE
        // Disable availability mode
        this.isAvailabilityMode$.next(false);
        this.availabilityParams$.next(null);
        this.availabilityFilterDisplay$.next(null);
        this.searchLocation = '';
        this.searchStartDate = null;
        this.searchStartTime = '';
        this.searchEndDate = null;
        this.searchEndTime = '';

        // Update standard search criteria
        this.searchCriteria$.next(parsedFilters);
        this.updateActiveFilterChips(parsedFilters);
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  onCitySelected(city: string): void {
    this.searchLocation = city;
    this.validateField('location');
  }

  onStartDateChange(event: MatDatepickerInputEvent<Date>, endPicker: MatDatepicker<Date>): void {
    this.searchStartDate = event.value ?? null;
    this.searchStartTime = '';
    this.searchEndTime = '';
    this.startTimeError = '';
    this.endTimeError = '';

    if (this.searchEndDate && this.searchStartDate && this.searchEndDate <= this.searchStartDate) {
      this.searchEndDate = null;
    }

    this.validateField('startDate');

    if (this.searchStartDate && endPicker?.open) {
      setTimeout(() => endPicker.open());
    }
  }

  onEndDateChange(event: MatDatepickerInputEvent<Date>): void {
    this.searchEndDate = event.value ?? null;
    this.searchEndTime = '';
    this.endTimeError = '';
    this.validateField('endDate');
  }

  searchAvailability(): void {
    this.clearSearchErrors();

    const location = this.searchLocation.trim();
    if (!location) {
      this.locationError = 'Unesite lokaciju';
    }

    const locationCanonical = this.toCanonical(location);
    const cityMatch = this.cities.some((city) => this.toCanonical(city) === locationCanonical);
    if (location && !cityMatch) {
      this.locationError = 'Odaberite grad iz liste';
    }

    if (!this.searchStartDate) {
      this.startDateError = 'Izaberite početni datum';
    }
    if (!this.searchEndDate) {
      this.endDateError = 'Izaberite krajnji datum';
    }
    if (!this.searchStartTime) {
      this.startTimeError = 'Izaberite početno vreme';
    }
    if (!this.searchEndTime) {
      this.endTimeError = 'Izaberite krajnje vreme';
    }

    if (
      this.locationError ||
      this.startDateError ||
      this.endDateError ||
      this.startTimeError ||
      this.endTimeError
    ) {
      return;
    }

    const normalizedLocation = this.cities.find(
      (city) => this.toCanonical(city) === locationCanonical
    ) ?? location;

    const todayStart = new Date();
    todayStart.setHours(0, 0, 0, 0);
    if ((this.searchStartDate as Date) < todayStart) {
      this.startDateError = 'Početni datum mora biti danas ili kasnije';
      return;
    }

    const startDateTime = new Date(this.searchStartDate as Date);
    const [startHour, startMinute] = this.searchStartTime.split(':').map((v) => parseInt(v, 10));
    startDateTime.setHours(startHour, startMinute);

    const endDateTime = new Date(this.searchEndDate as Date);
    const [endHour, endMinute] = this.searchEndTime.split(':').map((v) => parseInt(v, 10));
    endDateTime.setHours(endHour, endMinute);

    if (endDateTime <= startDateTime) {
      this.dateRangeError = 'Krajnji datum mora biti posle početnog';
      return;
    }

    // Combine date + time into ISO strings (matching home.component.ts)
    const startTimeISO = this.combineDateTime(this.searchStartDate as Date, this.searchStartTime);
    const endTimeISO = this.combineDateTime(this.searchEndDate as Date, this.searchEndTime);
    const pageSize = this.searchCriteria$.value.size ?? 20;

    const params = {
      location: normalizedLocation,
      startDate: '', // Not used, kept for compatibility
      startTime: startTimeISO, // ISO string: 2025-12-02T09:00:00
      endDate: '', // Not used, kept for compatibility
      endTime: endTimeISO, // ISO string: 2025-12-02T18:00:00
      page: 0,
      size: pageSize,
    };

    this.availabilityParams$.next(params);
    this.isAvailabilityMode$.next(true);
    this.availabilityFilterDisplay$.next(null);
    const startDate = this.formatDate(this.searchStartDate as Date);
    const endDate = this.formatDate(this.searchEndDate as Date);
    this.updateAvailabilityFilterDisplay(
      normalizedLocation,
      startDate,
      this.searchStartTime,
      endDate,
      this.searchEndTime
    );
    this.activeFilterChips$.next([]);
    this.searchCriteria$.next({ ...this.searchCriteria$.value, page: 0, size: pageSize });
  }

  onFiltersChanged(criteria: CarSearchCriteria): void {
    // Reset to page 0 when filters change
    const updated = { ...criteria, page: 0, size: this.searchCriteria$.value.size };
    this.searchCriteria$.next(updated);
    this.updateActiveFilterChips(updated);
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
    this.filtersOnlyReset();
  }

  onResetFilters(): void {
    this.filtersOnlyReset();
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

  private isCriteriaDefault(criteria: CarSearchCriteria): boolean {
    return (
      criteria.minPrice === undefined &&
      criteria.maxPrice === undefined &&
      criteria.make === undefined &&
      criteria.model === undefined &&
      criteria.minYear === undefined &&
      criteria.maxYear === undefined &&
      criteria.minSeats === undefined &&
      criteria.transmission === undefined &&
      (!criteria.features || criteria.features.length === 0) &&
      (!criteria.sort || criteria.sort === '' || criteria.sort === undefined) &&
      (!criteria.page || criteria.page === 0) &&
      (!criteria.size || criteria.size === 20)
    );
  }

  private filtersOnlyReset(): void {
    const defaultCriteria: CarSearchCriteria = {
      page: 0,
      size: this.searchCriteria$.value.size ?? 20,
    };

    this.searchCriteria$.next(defaultCriteria);
    this.updateActiveFilterChips(defaultCriteria);

    // Keep availability/search params intact; just sync filter params portion
    if (this.isAvailabilityMode$.value && this.availabilityParams$.value) {
      this.updateUrlParamsForAvailability(this.availabilityParams$.value);
    } else {
      this.updateUrlParams(defaultCriteria);
    }
  }

  resetAll(): void {
    this.searchLocation = '';
    this.searchStartDate = null;
    this.searchStartTime = '';
    this.searchEndDate = null;
    this.searchEndTime = '';
    this.clearSearchErrors();

    const defaultCriteria: CarSearchCriteria = { page: 0, size: 20 };
    this.searchCriteria$.next(defaultCriteria);
    this.activeFilterChips$.next([]);
    this.availabilityParams$.next(null);
    this.isAvailabilityMode$.next(false);
    this.availabilityFilterDisplay$.next(null);

    if (this.filtersComponent) {
      this.filtersComponent.resetFilters(false);
    }

    this.router.navigate(['/cars'], { replaceUrl: true });
  }

  private applyFilters(results: PagedResponse<Car>, criteria: CarSearchCriteria, isAvailability: boolean): PagedResponse<Car> {
    const hasFilters =
      (criteria.minPrice ?? null) !== null ||
      (criteria.maxPrice ?? null) !== null ||
      !!criteria.make ||
      !!criteria.model ||
      (criteria.minYear ?? null) !== null ||
      (criteria.maxYear ?? null) !== null ||
      (criteria.minSeats ?? null) !== null ||
      !!criteria.transmission ||
      (criteria.features && criteria.features.length > 0);

    if (!hasFilters) {
      return results;
    }

    const filtered = results.content.filter((car) => this.matchesFilters(car, criteria));

    const pageSize = isAvailability
      ? this.availabilityParams$.value?.size ?? results.pageSize ?? 20
      : this.searchCriteria$.value.size ?? results.pageSize ?? 20;
    const requestedPage = isAvailability
      ? this.availabilityParams$.value?.page ?? 0
      : this.searchCriteria$.value.page ?? results.currentPage ?? 0;

    const totalPages = Math.ceil(filtered.length / pageSize) || 0;
    const boundedPageIndex = Math.min(requestedPage, Math.max(totalPages - 1, 0));

    const start = boundedPageIndex * pageSize;
    const end = start + pageSize;
    const pagedContent = filtered.slice(start, end);

    if (isAvailability && this.availabilityParams$.value && boundedPageIndex !== requestedPage) {
      this.availabilityParams$.next({
        ...this.availabilityParams$.value,
        page: boundedPageIndex,
      });
    } else if (!isAvailability && boundedPageIndex !== requestedPage) {
      this.searchCriteria$.next({
        ...this.searchCriteria$.value,
        page: boundedPageIndex,
      });
    }

    return {
      ...results,
      content: pagedContent,
      totalElements: filtered.length,
      totalPages,
      currentPage: boundedPageIndex,
      pageSize,
      hasNext: boundedPageIndex < totalPages - 1,
      hasPrevious: boundedPageIndex > 0,
    };
  }

  private matchesFilters(car: Car, criteria: CarSearchCriteria): boolean {
    const makeCandidates = [car.make, (car as any).brand, car.model];
    const modelCandidates = [car.model, (car as any).brand, car.make];

    if (criteria.minPrice !== undefined && car.pricePerDay < criteria.minPrice) return false;
    if (criteria.maxPrice !== undefined && car.pricePerDay > criteria.maxPrice) return false;

    if (criteria.make) {
      const matchMake = makeCandidates.some((candidate) =>
        this.matchesAllTokens(candidate ?? '', criteria.make)
      );
      if (!matchMake) return false;
    }

    if (criteria.model) {
      const matchModel = modelCandidates.some((candidate) =>
        this.matchesAllTokens(candidate ?? '', criteria.model)
      );
      if (!matchModel) return false;
    }

    if (criteria.minYear !== undefined && car.year < criteria.minYear) return false;
    if (criteria.maxYear !== undefined && car.year > criteria.maxYear) return false;
    if (criteria.minSeats !== undefined && (car.seats ?? 0) < criteria.minSeats) return false;

    if (criteria.transmission) {
      if (car.transmissionType !== criteria.transmission) return false;
    }

    if (criteria.features && criteria.features.length > 0) {
      const carFeatures = car.features ?? [];
      const missing = criteria.features.some((f) => !carFeatures.includes(f));
      if (missing) return false;
    }

    return true;
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

  private formatDate(date: Date): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  /**
   * Combine date and time into ISO-8601 LocalDateTime string.
   * Format: YYYY-MM-DDTHH:mm:00
   */
  private combineDateTime(date: Date, time: string): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}T${time}:00`;
  }

  validateField(field: 'location' | 'startDate' | 'endDate'): void {
    switch (field) {
      case 'location':
        this.locationError = !this.searchLocation.trim() ? 'Unesite lokaciju' : '';
        break;
      case 'startDate':
        this.startDateError = !this.searchStartDate ? 'Izaberite početni datum' : '';
        break;
      case 'endDate':
        this.endDateError = !this.searchEndDate ? 'Izaberite krajnji datum' : '';
        if (this.searchStartDate && this.searchEndDate) {
          const start = new Date(this.searchStartDate);
          const end = new Date(this.searchEndDate);
          this.dateRangeError = end <= start ? 'Krajnji datum mora biti posle početnog' : '';
        }
        break;
    }
  }

  private clearSearchErrors(): void {
    this.locationError = '';
    this.startDateError = '';
    this.endDateError = '';
    this.startTimeError = '';
    this.endTimeError = '';
    this.dateRangeError = '';
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

    const filterParams = this.buildQueryParamsFromCriteria({
      ...this.searchCriteria$.value,
      page: undefined,
      size: undefined,
    });

    Object.assign(queryParams, filterParams);

    this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      replaceUrl: true,
    });
  }
}
